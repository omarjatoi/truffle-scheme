package scheme.runtime;

import java.util.ArrayList;
import java.util.List;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleStackTraceElement;
import com.oracle.truffle.api.bytecode.BytecodeLocation;
import com.oracle.truffle.api.bytecode.ContinuationRootNode;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;

import scheme.runtime.printer.Printer;
import scheme.runtime.value.SClosure;

/**
 * The loop of a prompt (design.md "Continuations", D4, D6): the program's root prompt and every
 * {@code %call-with-prompt}. It holds the prompt's pending segments and handles what comes back:
 *
 * <ul>
 * <li>a value: resume the innermost pending segment ({@link #resume}): from a fresh copy of its
 * frame if it is shared, from the original, once, if this driver alone owns it (design.md "Owned
 * segments"); a flattened prompt marker just passes the value on;</li>
 * <li>a {@link TailCall}: perform it;</li>
 * <li>a {@link Capture}: complete it here, or, if it is for another prompt, pass it on outward with
 * this prompt's pending segments and itself (as a marker) added;</li>
 * <li>an {@link InvokeContinuation} (full continuation): only the root prompt handles it;</li>
 * <li>an {@link EscapeException} (M5-04): the {@code %call/ec} prompt it is for returns its value;
 * a driver holding that prompt's marker drops the pending segments up to it.</li>
 * </ul>
 *
 * Everything is flat: re-entry costs O(1) Java stack depth, and finding a prompt costs the distance
 * to it. Installing a prompt is just a call, so {@code guard} (M3) doesn't capture on entry.
 *
 * <p>The pending segments are frames that wait for a value on the heap instead of the Java stack
 * (plan.md M6-04, design.md "Spilled frames in tools"). Tools see them all the same: the driver
 * keeps them in its root's frame ({@link #PENDING_SLOT}) whenever it calls out, and its root reports
 * them as its asynchronous stack ({@link #pendingFrames}), which the debugger and the CPU sampler
 * show after the frames on the Java stack. An uncaught error that leaves the driver unwinds through
 * them first ({@link #unwind}), so its guest stack trace has every frame.
 */
final class PromptDriver extends Node {

    /** The slot of the prompt root's frame that holds the pending segments while the driver calls. */
    static final int PENDING_SLOT = 0;

    /**
     * At most this many pending frames are unwound through or reported to tools, innermost first,
     * like a JVM's limit on stack trace depth: the frames of a recursion a million deep are all on the
     * heap, but a stack trace that long helps no one.
     */
    static final int MAX_TRACED_FRAMES = 10_000;

    /** An error to raise for the innermost pending frame. */
    private record PendingRaise(Object condition) {
    }

    /** Performs the calls and tail calls the driver makes, through an inline cache (plan.md M5-10). */
    @Child private Trampoline calls = Trampoline.create();
    @Child private ResumeNode resumeNode = PromptDriverFactory.ResumeNodeGen.create();
    /** Created the first time an uncaught error unwinds (see {@link #unwind}). */
    private RootCallTarget unwindTarget;

    PromptDriver() {
    }

    /** The frame descriptor of a root with a prompt driver: it has the {@link #PENDING_SLOT}. */
    static FrameDescriptor frameDescriptor() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder(1);
        int slot = builder.addSlot(FrameSlotKind.Object, "pending", null);
        assert slot == PENDING_SLOT;
        return builder.build();
    }

    /**
     * Continues a prompt with {@code tag}: {@code first} is what its body produced, and
     * {@code initialPending} the segments still to resume. Returns the prompt's value.
     * {@code frame} is the prompt root's: the driver keeps the pending segments in it for tools.
     */
    Object run(VirtualFrame frame, Object tag, Object first, SegmentList initialPending, SchemeContext context) {
        DynEnv promptEnv = context.getDynamicEnv();
        SegmentList pending = initialPending;
        Object value = first;
        while (true) {
            try {
                if (value instanceof PendingRaise r) {
                    try {
                        frame.setObject(PENDING_SLOT, pending);
                        value = callWithProcedure(context.raise, new Object[]{null, r.condition()});
                    } catch (SchemeError e) {
                        // raise itself failed: the frame it was raising for is unwound too, and
                        // the error goes on to the one after it (the SchemeError catch below).
                        // Handled here, so a jump or escape out of raise leaves nothing behind. An
                        // error that leaves the driver instead unwinds through that frame too.
                        if (e.isRaisable() && context.raise instanceof SClosure) {
                            pending = pending.tail;
                        }
                        throw e;
                    }
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
                            // Torture checks every resume (rule 3): so every segment is shared.
                            context.shareSegments();
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
                            context.spills++;
                            // Only this driver holds the spilled frames: it resumes them in place.
                            capture.claim(context);
                            pending = capture.segmentsBefore(pending);
                            Object[] arguments = (Object[]) capture.value;
                            frame.setObject(PENDING_SLOT, pending);
                            value = callWithProcedure(arguments[0], arguments);
                            continue;
                        }
                        case FULL: {
                            if (tag != Capture.ROOT_TAG) {
                                capture.addOuter(pending, new Capture.PromptMarker(tag, promptEnv));
                                return capture;
                            }
                            Continuation k = Continuation.capture(capture.segmentsBefore(pending), capture.dynEnv, false, context);
                            pending = k.segments;
                            frame.setObject(PENDING_SLOT, pending);
                            value = receive(context, capture.receiver, k, capture.dynEnv, capture.dynEnv);
                            continue;
                        }
                        default: {
                            if (!Capture.sameTag(tag, capture.tag) && !capture.hasPrompt(capture.tag) && !SegmentList.hasPrompt(pending, capture.tag)) {
                                // Not this prompt, nor one flattened into the capture or this
                                // driver's pending segments: pass it on (segmentsBefore hands the
                                // capture's list over, so it is only called once the capture stays).
                                if (tag == Capture.ROOT_TAG) {
                                    throw noPrompt(capture.tag);
                                }
                                capture.addOuter(pending, new Capture.PromptMarker(tag, promptEnv));
                                return capture;
                            }
                            SegmentList all = capture.segmentsBefore(pending);
                            SegmentList.Split split = SegmentList.splitAt(all, capture.tag);
                            if (split != null) {
                                // A prompt flattened into this driver's pending segments.
                                pending = split.after();
                                frame.setObject(PENDING_SLOT, pending);
                                value = receive(context, capture.receiver, Continuation.capture(split.before(), capture.dynEnv, true, context), capture.dynEnv, split.marker().dynEnv());
                            } else {
                                // This prompt: abort to it.
                                pending = null;
                                frame.setObject(PENDING_SLOT, pending);
                                value = receive(context, capture.receiver, Continuation.capture(all, capture.dynEnv, true, context), capture.dynEnv, promptEnv);
                            }
                            continue;
                        }
                    }
                } else if (value instanceof TailCall tail) {
                    // The escape prompt (M5-04) whose return this tail call's continuation is, if
                    // any: this one with nothing pending, or one flattened next in line.
                    EscapeRootNode.Target target = null;
                    if (pending == null) {
                        if (tag instanceof EscapeRootNode.Target t) {
                            target = t;
                        }
                    } else if (pending.head instanceof Capture.PromptMarker marker && marker.tag() instanceof EscapeRootNode.Target t) {
                        target = t;
                    }
                    if (target != null) {
                        if (EscapeRootNode.escapesTo(tail.procedure, target, context)) {
                            // k, tail-called in its own prompt's tail position: no need to throw.
                            value = EscapeRootNode.values(tail.arguments);
                            if (pending == null) {
                                return value;
                            }
                            pending = pending.tail;
                            continue;
                        }
                        if (tail.arguments.length == 2 && tail.procedure instanceof SClosure c && c.callTarget.getRootNode() instanceof EscapeRootNode callEC) {
                            // %call/ec in that prompt's tail position has the same continuation, so it
                            // runs in that prompt, in constant space (R7RS §3.5).
                            frame.setObject(PENDING_SLOT, pending);
                            value = callWithProcedure(tail.arguments[1], new Object[]{null, callEC.continuation(target, context)});
                            continue;
                        }
                    }
                    frame.setObject(PENDING_SLOT, pending);
                    value = callWithProcedure(tail.procedure, tail.arguments);
                } else if (pending == null) {
                    return value;
                } else {
                    Object head = pending.head;
                    pending = pending.tail;
                    if (head instanceof Capture.Segment segment) {
                        frame.setObject(PENDING_SLOT, pending);
                        value = resume(segment, value, context);
                    }
                    // A PromptMarker: that prompt returns the value normally.
                }
            } catch (SchemeError e) {
                // An error escaped a frame this driver called (a resumed segment, a tail call, a
                // spilled call). Unsegmented, it would have reached the call site of the next pending
                // frame, which raises it there (design.md D8): so raise it for that frame. If raise
                // itself fails, that frame is unwound too, and the error goes to the one after it
                // (the PendingRaise case drops it).
                while (pending != null && !(pending.head instanceof Capture.Segment)) {
                    // A flattened inner prompt: the error leaves it, to the frame that installed it.
                    pending = pending.tail;
                }
                if (!e.isRaisable() || !(context.raise instanceof SClosure) || pending == null) {
                    throw unwind(e, pending);
                }
                value = new PendingRaise(e.getErrorObject());
            } catch (EscapeException e) {
                if (e.target == tag) {
                    return e.value;
                }
                SegmentList marker = SegmentList.markerOf(pending, e.target);
                if (marker == null) {
                    if (tag == Capture.ROOT_TAG) {
                        throw escapedTooFar();
                    }
                    throw e;
                }
                pending = marker.tail;
                value = e.value;
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

    /**
     * Resumes a pending segment, which the driver has just taken off its list, with {@code value}
     * (AGENTS.md rule 3, design.md "Owned segments"). A shared segment, one that a continuation may
     * reach, is resumed from a fresh copy of its frame, so that it can be resumed again. A segment
     * this driver alone owns (a spill's) is resumed from its original frame, as the Bytecode DSL's
     * own one-shot resume does: nothing can reach the segment any more, so it is never resumed
     * again, and the frame goes on to suspend into a new segment if it suspends again.
     */
    private Object resume(Capture.Segment segment, Object value, SchemeContext context) {
        if (segment.isOwned(context)) {
            context.inPlaceResumes++;
            return resumeNode.execute(segment.root(), segment.frame(), value);
        }
        context.copiedResumes++;
        if (SchemeRootNode.TORTURE) {
            // Rule 3: resuming must never change the segment. Resume one copy, then check the
            // original against a second, untouched copy.
            MaterializedFrame witness = copy(segment.frame());
            Object result = resumeNode.execute(segment.root(), copy(segment.frame()), value);
            checkUnchanged(segment.frame(), witness);
            return result;
        }
        return resumeNode.execute(segment.root(), copy(segment.frame()), value);
    }

    /**
     * Hands continuation k to a receiver through the prelude's {@code %receive} (control.scm), which
     * rewinds from {@code from} (capture time) to {@code to} (where the receiver runs) and wraps k in
     * a procedure. Rewinding runs Scheme thunks, so it happens in Scheme (design.md D5).
     */
    private Object receive(SchemeContext context, Object receiver, Continuation k, DynEnv from, DynEnv to) {
        return callWithProcedure(receiveProcedure(context), new Object[]{null, receiver, k, from, to});
    }

    /** What the prelude's {@code %unwind-root} returns once it has run every after-thunk ({@link #leave}). */
    private static final Object UNWOUND = new Object();

    /**
     * {@code error} leaves a root prompt (a program, a REPL input, a library body, an embedder's
     * call): control leaves the dynamic extent of every {@code dynamic-wind} it skipped, so their
     * after-thunks run, innermost first, each in its own dynamic environment, as an escape runs them
     * (design.md D8). They are Scheme code, so they run through the prelude's {@code %unwind-root}
     * (control.scm), under this prompt (D5); then the error goes on. {@code outer} is the dynamic
     * environment the prompt started in. An after-thunk may transfer control instead (call a
     * continuation of this prompt, or exit): the prompt then goes on from there and returns what it
     * returns. An error that leaves an after-thunk replaces {@code error}, and goes on through the
     * after-thunks outside it.
     */
    Object leave(VirtualFrame frame, SchemeError error, DynEnv outer, SchemeContext context) {
        SchemeError leaving = error;
        while (true) {
            Object unwind = context.unwindRoot;
            if (context.getDynamicEnv().winders == outer.winders || !(unwind instanceof SClosure)) {
                throw leaving;
            }
            Object result;
            try {
                result = run(frame, Capture.ROOT_TAG, new TailCall(unwind, new Object[]{unwind, outer, UNWOUND}), null, context);
            } catch (SchemeError e) {
                leaving = e;
                continue;
            }
            if (result != UNWOUND) {
                return result;
            }
            throw leaving;
        }
    }

    private static Object receiveProcedure(SchemeContext context) {
        Object receive = context.receive;
        if (receive == null) {
            throw new IllegalStateException("the prelude has not installed %receive");
        }
        return receive;
    }

    /**
     * An error leaves this prompt with {@code pending} frames still waiting for a value. Unsegmented,
     * it would have left through each of them, and so it does here: each frame, resumed from a copy
     * with the error as its value, throws it from its call site ({@code Resumed}). The error's guest
     * stack trace then has every frame, in order (plan.md M6-04). Returns the error.
     *
     * <p>A frame's location in a guest stack trace is the node through which it called the frame
     * inside it, so each frame is called with the next one's bytecode as its location, and the last
     * with this driver, the prompt's location. The frame inside the first pending one was called by
     * this driver, though: so the error first passes through a hidden root ({@link UnwindRootNode})
     * called with the first pending frame's bytecode as its location. A loop: Java stack depth stays
     * constant (AGENTS.md rule 4).
     */
    @TruffleBoundary
    private SchemeError unwind(SchemeError error, SegmentList pending) {
        List<Capture.Segment> frames = new ArrayList<>();
        for (SegmentList l = pending; l != null && frames.size() < MAX_TRACED_FRAMES; l = l.tail) {
            if (l.head instanceof Capture.Segment segment) {
                frames.add(segment);
            }
        }
        if (frames.isEmpty()) {
            return error;
        }
        if (unwindTarget == null) {
            unwindTarget = new UnwindRootNode(getRootNode().getLanguage(SchemeLanguage.class)).getCallTarget();
        }
        rethrown(error, unwindTarget, location(frames.get(0)), error);
        for (int i = 0; i < frames.size(); i++) {
            Capture.Segment segment = frames.get(i);
            Node location = i + 1 < frames.size() ? location(frames.get(i + 1)) : this;
            rethrown(error, segment.root().getCallTarget(), location, copy(segment.frame()), error);
        }
        return error;
    }

    /** Calls {@code target}, which throws {@code error} (any other exception goes on). */
    private static void rethrown(SchemeError error, RootCallTarget target, Node location, Object... arguments) {
        try {
            target.call(location, arguments);
        } catch (SchemeError e) {
            if (e != error) {
                throw e;
            }
            return;
        }
        throw new IllegalStateException("an unwound frame returned instead of throwing its error");
    }

    /** The bytecode a suspended frame resumes in: a location in it names the frame's call site. */
    private static Node location(Capture.Segment segment) {
        return segment.root().getLocation().getBytecodeNode();
    }

    /**
     * The frames pending in the frame of a prompt's root ({@link #PENDING_SLOT}), innermost first, as
     * its asynchronous stack: what {@code RootNode.findAsynchronousFrames} returns. Each element is
     * the suspended procedure's frame at its call site, so tools show its name, location and
     * variables. A flattened prompt is not a frame of its own. At most {@link #MAX_TRACED_FRAMES}.
     * Only tools call it, on a slow path (it can't be a boundary: it takes a frame).
     */
    static List<TruffleStackTraceElement> pendingFrames(Frame frame) {
        if (!(frame.getValue(PENDING_SLOT) instanceof SegmentList pending)) {
            return null;
        }
        List<TruffleStackTraceElement> elements = new ArrayList<>();
        for (SegmentList l = pending; l != null && elements.size() < MAX_TRACED_FRAMES; l = l.tail) {
            if (l.head instanceof Capture.Segment segment) {
                BytecodeLocation location = segment.root().getLocation();
                RootNode procedure = (RootNode) segment.root().getSourceRootNode();
                elements.add(TruffleStackTraceElement.create(location.getBytecodeNode(), procedure.getCallTarget(), segment.frame(), location.getBytecodeIndex()));
            }
        }
        return elements;
    }

    /**
     * Throws its argument, an error: the hidden frame an unwinding error passes through first, so
     * that the first pending frame gets its location (see {@link #unwind}).
     */
    static final class UnwindRootNode extends RootNode {

        UnwindRootNode(SchemeLanguage language) {
            super(language);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            throw (SchemeError) frame.getArguments()[0];
        }

        @Override
        public boolean isInternal() {
            return true;
        }

        @Override
        public String getName() {
            return "<unwind>";
        }

        @Override
        public String toString() {
            return "<unwind>";
        }
    }

    Object callThunk(Object procedure) {
        return calls.executeCall(procedure, new Object[]{procedure});
    }

    /** Index 0 of {@code arguments} is (or is set to) the procedure. */
    Object callWithProcedure(Object procedure, Object[] arguments) {
        if (!(procedure instanceof SClosure closure)) {
            throw notAProcedure(procedure);
        }
        arguments[0] = closure;
        return calls.executeCall(closure, arguments);
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

    /** An escape-only continuation invoked outside its extent: the compiler's proof was wrong (M5-04). */
    @TruffleBoundary
    private static IllegalStateException escapedTooFar() {
        return new IllegalStateException("an escape-only continuation was invoked outside its call/cc's extent");
    }

    @TruffleBoundary
    private static SchemeError noPrompt(Object tag) {
        return new SchemeError("no prompt with tag " + Printer.print(tag, Printer.Mode.WRITE));
    }

    /**
     * A copy of a suspended frame (design.md "Copying a frame"): the arguments and every slot,
     * operand stack included. A shared segment's original is never resumed, so every resume of it is
     * independent.
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
