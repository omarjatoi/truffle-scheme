package scheme.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.bytecode.ContinuationRootNode;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
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

    @Child private IndirectCallNode callNode = IndirectCallNode.create();
    @Child private ResumeNode resumeNode = PromptDriverFactory.ResumeNodeGen.create();
    private final SchemeLanguage language;

    PromptDriver(SchemeLanguage language) {
        this.language = language;
    }

    /**
     * Continues a prompt with {@code tag}: {@code first} is what its body produced, and
     * {@code initialPending} the segments still to resume. Returns the prompt's value.
     */
    Object run(Object tag, Object first, SegmentList initialPending, SchemeContext context) {
        DynEnv promptEnv = context.getDynamicEnv();
        SegmentList pending = initialPending;
        Object value = first;
        while (true) {
            try {
                if (value instanceof Capture capture) {
                    switch (capture.kind) {
                        case REINSTATE: {
                            // Composable continuation: its segments go on top of the invoker's.
                            pending = SegmentList.concat(capture.continuation.segments, capture.segmentsBefore(pending));
                            value = capture.value;
                            continue;
                        }
                        case FULL: {
                            if (tag != Capture.ROOT_TAG) {
                                capture.addOuter(pending, new Capture.PromptMarker(tag, promptEnv));
                                return capture;
                            }
                            Continuation k = new Continuation(capture.segmentsBefore(pending), capture.dynEnv, false);
                            pending = k.segments;
                            value = call(capture.receiver, language.continuationProcedure(k));
                            continue;
                        }
                        default: {
                            SegmentList all = capture.segmentsBefore(pending);
                            SegmentList.Split split = SegmentList.splitAt(all, capture.tag);
                            if (split != null) {
                                // A prompt flattened into this driver's pending segments.
                                pending = split.after();
                                context.setDynamicEnv(split.marker().dynEnv());
                                value = call(capture.receiver, language.continuationProcedure(new Continuation(split.before(), capture.dynEnv, true)));
                            } else if (Capture.sameTag(tag, capture.tag)) {
                                // This prompt: abort to it.
                                pending = null;
                                context.setDynamicEnv(promptEnv);
                                value = call(capture.receiver, language.continuationProcedure(new Continuation(all, capture.dynEnv, true)));
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
                        value = resumeNode.execute(segment.root(), copy(segment.frame()), value);
                    }
                    // A PromptMarker: that prompt returns the value normally.
                }
            } catch (InvokeContinuation e) {
                if (tag != Capture.ROOT_TAG) {
                    throw e;
                }
                pending = e.continuation.segments;
                context.setDynamicEnv(e.continuation.dynEnv);
                value = e.value;
            }
        }
    }

    Object call(Object procedure, Object argument) {
        if (!(procedure instanceof SClosure closure)) {
            throw notAProcedure(procedure);
        }
        return callNode.call(closure.callTarget, closure, argument);
    }

    Object callThunk(Object procedure) {
        if (!(procedure instanceof SClosure closure)) {
            throw notAProcedure(procedure);
        }
        return callNode.call(closure.callTarget, closure);
    }

    /** {@code arguments} already includes the procedure at index 0. */
    private Object callWithProcedure(Object procedure, Object[] arguments) {
        if (!(procedure instanceof SClosure closure)) {
            throw notAProcedure(procedure);
        }
        return callNode.call(closure.callTarget, arguments);
    }

    @TruffleBoundary
    private static SchemeError notAProcedure(Object value) {
        return new SchemeError("not a procedure: " + Printer.print(value, Printer.Mode.WRITE));
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
