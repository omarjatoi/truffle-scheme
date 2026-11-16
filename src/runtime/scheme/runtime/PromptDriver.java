package scheme.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.bytecode.ContinuationRootNode;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;

import scheme.runtime.printer.Printer;
import scheme.runtime.value.SClosure;

/**
 * The loop of a prompt (design.md "Continuations", D4, D6): the program's root prompt and every
 * {@code %call-with-prompt}. It holds the prompt's pending segments and handles what comes back:
 *
 * <ul>
 * <li>a value: resume the innermost pending segment from a fresh copy of its frame, never the
 * original; a flattened prompt marker just passes the value on;</li>
 * <li>a {@link TailCall}: perform it;</li>
 * <li>a {@link Capture}: complete it here, or, if it is for another prompt, pass it on outward with
 * this prompt's pending segments and itself (as a marker) added;</li>
 * <li>an {@link InvokeContinuation} (full continuation): only the root prompt handles it.</li>
 * </ul>
 *
 * Everything is flat: re-entry costs O(1) Java stack depth, and finding a prompt costs the distance
 * to it. Installing a prompt is just a call, so {@code guard} (M3) doesn't capture on entry.
 */
final class PromptDriver extends Node {

    /** An error to raise for the innermost pending frame. */
    private record PendingRaise(Object condition) {
    }

    @Child private IndirectCallNode callNode = IndirectCallNode.create();
    @Child private ResumeNode resumeNode = PromptDriverFactory.ResumeNodeGen.create();

    /**
     * EXPLORATION: a root prompt with foreign frames between it and its caller (an interop call):
     * full continuations it didn't capture pass through it.
     */
    private final boolean barrier;

    PromptDriver() {
        this(false);
    }

    PromptDriver(boolean barrier) {
        this.barrier = barrier;
    }

    /**
     * Continues a prompt with {@code tag}: {@code first} is what its body produced, and
     * {@code initialPending} the segments still to resume. Returns the prompt's value.
     */
    Object run(Object tag, Object first, SegmentList initialPending, SchemeContext context) {
        DynEnv promptEnv = context.getDynamicEnv();
        SegmentList pending = initialPending;
        Object value = first;
        // True while calling raise for the innermost pending segment (see the SchemeError catch).
        boolean raising = false;
        while (true) {
            try {
                if (value instanceof PendingRaise r) {
                    raising = true;
                    value = callWithProcedure(context.raise, new Object[]{null, r.condition()});
                    raising = false;
                    continue;
                }
                if (value instanceof Capture capture) {
                    switch (capture.kind) {
                        case REINSTATE: {
                            // Composable continuation: its segments go on top of the invoker's.
                            pending = SegmentList.concat(capture.continuation.segments, capture.segmentsBefore(pending));
                            value = capture.value;
                            continue;
                        }
                        case TORTURE: {
                            if (tag != Capture.ROOT_TAG) {
                                capture.addOuter(pending, new Capture.PromptMarker(tag, promptEnv));
                                return capture;
                            }
                            context.tortureCaptures++;
                            pending = capture.segmentsBefore(pending);
                            value = capture.value;
                            continue;
                        }
                        case SPILL: {
                            // Only the root prompt resets the depth: inner prompts pass it on.
                            if (tag != Capture.ROOT_TAG) {
                                capture.addOuter(pending, new Capture.PromptMarker(tag, promptEnv));
                                return capture;
                            }
                            pending = capture.segmentsBefore(pending);
                            Object[] arguments = (Object[]) capture.value;
                            value = callWithProcedure(arguments[0], arguments);
                            continue;
                        }
                        case FULL: {
                            if (tag != Capture.ROOT_TAG) {
                                capture.addOuter(pending, new Capture.PromptMarker(tag, promptEnv));
                                return capture;
                            }
                            Continuation k = new Continuation(capture.segmentsBefore(pending), capture.dynEnv, false);
                            k.owner = context.rootRun;
                            pending = k.segments;
                            value = receive(context, capture.receiver, k, capture.dynEnv, capture.dynEnv);
                            continue;
                        }
                        default: {
                            SegmentList all = capture.segmentsBefore(pending);
                            SegmentList.Split split = SegmentList.splitAt(all, capture.tag);
                            if (split != null) {
                                // A prompt flattened into this driver's pending segments.
                                pending = split.after();
                                value = receive(context, capture.receiver, new Continuation(split.before(), capture.dynEnv, true), capture.dynEnv, split.marker().dynEnv());
                            } else if (Capture.sameTag(tag, capture.tag)) {
                                // This prompt: abort to it.
                                pending = null;
                                value = receive(context, capture.receiver, new Continuation(all, capture.dynEnv, true), capture.dynEnv, promptEnv);
                            } else if (tag == Capture.ROOT_TAG) {
                                throw noPrompt(capture.tag);
                            } else {
                                capture.addOuter(pending, new Capture.PromptMarker(tag, promptEnv));
                                return capture;
                            }
                            continue;
                        }
                    }
                } else if (value instanceof TailCall tail) {
                    value = callWithProcedure(tail.procedure, tail.arguments);
                } else if (pending == null) {
                    return value;
                } else {
                    Object head = pending.head;
                    pending = pending.tail;
                    if (head instanceof Capture.Segment segment) {
                        if (SchemeRootNode.TORTURE) {
                            // Rule 3: resuming must never change the segment. Resume one copy, then
                            // check the original against a second, untouched copy.
                            MaterializedFrame witness = copy(segment.frame());
                            value = resumeNode.execute(segment.root(), copy(segment.frame()), value);
                            checkUnchanged(segment.frame(), witness);
                        } else {
                            value = resumeNode.execute(segment.root(), copy(segment.frame()), value);
                        }
                    }
                    // A PromptMarker: that prompt returns the value normally.
                }
            } catch (SchemeError e) {
                // An error escaped a frame this driver called (a resumed segment, a tail call, a
                // spilled call). Unsegmented, it would have reached the call site of the next pending
                // frame, which raises it there (design.md D8): so raise it for that frame. If raise
                // itself fails, that frame is unwound too, and the error goes to the one after it.
                if (raising) {
                    raising = false;
                    pending = pending.tail;
                }
                while (pending != null && !(pending.head instanceof Capture.Segment)) {
                    // A flattened inner prompt: the error leaves it, to the frame that installed it.
                    pending = pending.tail;
                }
                if (!e.isRaisable() || !(context.raise instanceof SClosure) || pending == null) {
                    throw e;
                }
                value = new PendingRaise(e.getErrorObject());
            } catch (InvokeContinuation e) {
                if (tag != Capture.ROOT_TAG || (barrier && e.continuation.owner != context.rootRun)) {
                    throw e;
                }
                pending = e.continuation.segments;
                context.setDynamicEnv(e.continuation.dynEnv);
                value = e.value;
            }
        }
    }

    /**
     * Hands continuation k to a receiver through the prelude's {@code %receive} (control.scm), which
     * rewinds from {@code from} (capture time) to {@code to} (where the receiver runs) and wraps k in
     * a procedure. Rewinding runs Scheme thunks, so it happens in Scheme (design.md D5).
     */
    private Object receive(SchemeContext context, Object receiver, Continuation k, DynEnv from, DynEnv to) {
        return callWithProcedure(receiveProcedure(context), new Object[]{null, receiver, k, from, to});
    }

    private static Object receiveProcedure(SchemeContext context) {
        Object receive = context.receive;
        if (receive == null) {
            throw new IllegalStateException("the prelude has not installed %receive");
        }
        return receive;
    }

    Object callThunk(Object procedure) {
        if (!(procedure instanceof SClosure closure)) {
            throw notAProcedure(procedure);
        }
        return callNode.call(closure.callTarget, closure);
    }

    /** Index 0 of {@code arguments} is (or is set to) the procedure. */
    Object callWithProcedure(Object procedure, Object[] arguments) {
        if (!(procedure instanceof SClosure closure)) {
            throw notAProcedure(procedure);
        }
        arguments[0] = closure;
        return callNode.call(closure.callTarget, arguments);
    }

    @TruffleBoundary
    private static SchemeError notAProcedure(Object value) {
        return new SchemeError("not a procedure: " + Printer.print(value, Printer.Mode.WRITE));
    }

    /** Torture mode: the original frame of a resumed segment still equals a copy taken before. */
    @TruffleBoundary
    private static void checkUnchanged(MaterializedFrame original, MaterializedFrame witness) {
        Object[] a = original.getArguments();
        Object[] b = witness.getArguments();
        boolean same = a.length == b.length;
        for (int i = 0; same && i < a.length; i++) {
            same = a[i] == b[i];
        }
        int slots = original.getFrameDescriptor().getNumberOfSlots();
        for (int i = 0; same && i < slots; i++) {
            byte tag = original.getTag(i);
            same = tag == witness.getTag(i) && (tag == FrameSlotKind.Illegal.tag || java.util.Objects.equals(original.getValue(i), witness.getValue(i)));
        }
        if (!same) {
            // An internal error, never a Scheme error (design.md D8): a resume mutated its segment.
            throw new IllegalStateException("torture: resuming a continuation segment changed its frame (AGENTS.md rule 3)");
        }
    }

    @TruffleBoundary
    private static SchemeError noPrompt(Object tag) {
        return new SchemeError("no prompt with tag " + Printer.print(tag, Printer.Mode.WRITE));
    }

    /**
     * A copy of a suspended frame (design.md "Copying a frame"): the arguments and every slot,
     * operand stack included. The original is never resumed, so every resume is independent.
     */
    static MaterializedFrame copy(MaterializedFrame frame) {
        FrameDescriptor descriptor = frame.getFrameDescriptor();
        MaterializedFrame copy = Truffle.getRuntime().createMaterializedFrame(frame.getArguments().clone(), descriptor);
        frame.copyTo(0, copy, 0, descriptor.getNumberOfSlots());
        return copy;
    }

    /** Resumes a segment through a DirectCallNode cache keyed on its ContinuationRootNode (M0-02, M0-03). */
    @GenerateInline(false)
    abstract static class ResumeNode extends Node {
        abstract Object execute(ContinuationRootNode root, MaterializedFrame frame, Object value);

        @Specialization(guards = "root == cachedRoot", limit = "8")
        static Object doDirect(@SuppressWarnings("unused") ContinuationRootNode root, MaterializedFrame frame, Object value,
                        @Cached("root") ContinuationRootNode cachedRoot,
                        @Cached("create(cachedRoot.getCallTarget())") DirectCallNode callNode) {
            return callNode.call(frame, value);
        }

        @Specialization(replaces = "doDirect")
        static Object doIndirect(ContinuationRootNode root, MaterializedFrame frame, Object value,
                        @Cached IndirectCallNode callNode) {
            return callNode.call(root.getCallTarget(), frame, value);
        }
    }
}
