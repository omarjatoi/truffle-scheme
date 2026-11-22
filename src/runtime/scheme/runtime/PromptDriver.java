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
import com.oracle.truffle.api.instrumentation.ProbeNode;
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
 * this prompt's pending segments and itself (as a marker) added; an APPLY capture (a full
 * continuation invoked) only the root prompt handles, and the others pass it on as it is;</li>
 * <li>an ESCAPE capture (M5-04, an escape-only continuation invoked): the {@code %call/ec} prompt
 * it is for returns its value; a driver holding that prompt's marker drops the pending segments up
 * to it; any other passes it on as it is.</li>
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
 * them first ({@link #unwind}), so its guest stack trace has every frame. And an instrument that
 * counts frames ({@code sandbox.MaxStackFrames}) counts them too ({@link PendingFramesNode}).
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
    /** What instruments that count frames see of the pending ones (design.md D10, "Sandboxing"). */
    @Child private PendingFramesNode pendingFrames = new PendingFramesNode();
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
     * Continues a prompt with {@code tag}: {@code value} is what its body produced, and
     * {@code initialPending} the segments still to resume. Returns the prompt's value.
     * {@code frame} is the prompt root's: the driver keeps the pending segments in it for tools.
     *
     * <p>No Java local keeps what the program has gone on with (design.md "Tail calls keep nothing
     * alive"): {@code value} is overwritten by each step's result, a capture's value is taken from
     * it, and a resumed frame gets its value in a {@link Resumption}, which it empties.
     *
     * <p>Every segment in {@code pending} is one of the program's frames: an instrument that counts
     * frames sees it enter as it joins the list and return as it leaves, resumed or dropped
     * ({@link #enterPending}, {@link #leavePending}). Whatever the driver still holds when it returns
     * or throws, it drops.
     */
    Object run(VirtualFrame frame, Object tag, Object value, SegmentList initialPending, SchemeContext context) {
        DynEnv promptEnv = context.getDynamicEnv();
        SegmentList pending = initialPending;
        enterPending(frame, pending, null);
        try {
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
                                leavePending(frame, pending, pending.tail);
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
                                SegmentList outer = pending;
                                pending = SegmentList.concat(capture.continuation.segments, capture.segmentsBefore(pending));
                                enterPending(frame, pending, outer);
                                if (SchemeLogs.on(SchemeLogs.CONTINUATIONS)) {
                                    logContinuation("reinstated a composable continuation of ", capture.continuation.segments, null, "");
                                }
                                value = capture.takeValue();
                                continue;
                            }
                            case APPLY: {
                                // A full continuation invoked: it replaces the current one, up to the
                                // root prompt (design.md "Continuations").
                                if (tag != Capture.ROOT_TAG) {
                                    return capture;
                                }
                                leavePending(frame, pending, null);
                                pending = capture.continuation.segments;
                                enterPending(frame, pending, null);
                                if (SchemeLogs.on(SchemeLogs.CONTINUATIONS)) {
                                    logContinuation("re-entered a continuation of ", pending, null, ", in place of the current one");
                                }
                                context.setDynamicEnv(capture.continuation.dynEnv);
                                value = capture.takeValue();
                                continue;
                            }
                            case ESCAPE: {
                                // An escape-only continuation invoked (M5-04): its prompt returns.
                                if (capture.tag == tag) {
                                    return capture.takeValue();
                                }
                                SegmentList marker = SegmentList.markerOf(pending, capture.tag);
                                if (marker == null) {
                                    if (tag == Capture.ROOT_TAG) {
                                        throw escapedTooFar();
                                    }
                                    // Abandoned with the rest of this prompt.
                                    return capture;
                                }
                                // Its prompt is flattened into this driver's pending segments.
                                leavePending(frame, pending, marker);
                                pending = marker.tail;
                                value = capture.takeValue();
                                continue;
                            }
                            case TORTURE: {
                                if (tag != Capture.ROOT_TAG) {
                                    capture.addOuter(pending, new Capture.PromptMarker(tag, promptEnv));
                                    return capture;
                                }
                                context.tortureCaptures++;
                                if (SchemeRootNode.TORTURE_OWNED) {
                                    // Owned torture: only this driver holds the frames, as a spill's.
                                    capture.claim(context);
                                } else {
                                    // Torture checks every resume (rule 3): so every segment is shared.
                                    context.shareSegments();
                                }
                                SegmentList outer = pending;
                                pending = capture.segmentsBefore(pending);
                                enterPending(frame, pending, outer);
                                value = capture.takeValue();
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
                                SegmentList outer = pending;
                                pending = capture.segmentsBefore(pending);
                                enterPending(frame, pending, outer);
                                if (SchemeLogs.on(SchemeLogs.SPILL)) {
                                    logSpill(context, pending, outer);
                                }
                                Object[] arguments = (Object[]) capture.takeValue();
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
                                if (SchemeLogs.on(SchemeLogs.CONTINUATIONS)) {
                                    logContinuation("captured a continuation of ", k.segments, null, "");
                                }
                                enterPending(frame, k.segments, pending);
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
                                SegmentList outer = pending;
                                SegmentList all = capture.segmentsBefore(pending);
                                SegmentList.Split split = SegmentList.splitAt(all, capture.tag);
                                if (split != null) {
                                    // A prompt flattened into this driver's pending segments: the
                                    // frames before it leave with the continuation.
                                    enterPending(frame, all, outer);
                                    leavePending(frame, split.before(), null);
                                    if (SchemeLogs.on(SchemeLogs.CONTINUATIONS)) {
                                        logContinuation("captured a composable continuation of ", split.before(), null, ", up to a prompt inside");
                                    }
                                    pending = split.after();
                                    frame.setObject(PENDING_SLOT, pending);
                                    if (!language().noTags.isValid()) {
                                        endForTools(split.before(), context);
                                    }
                                    value = receive(context, capture.receiver, Continuation.capture(split.before(), capture.dynEnv, true, context), capture.dynEnv, split.marker().dynEnv());
                                } else {
                                    // This prompt: abort to it.
                                    leavePending(frame, outer, null);
                                    if (SchemeLogs.on(SchemeLogs.CONTINUATIONS)) {
                                        logContinuation("captured a composable continuation of ", all, null, ", up to this prompt");
                                    }
                                    pending = null;
                                    frame.setObject(PENDING_SLOT, pending);
                                    if (!language().noTags.isValid()) {
                                        endForTools(all, context);
                                    }
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
                        leavePending(frame, pending, pending.tail);
                        pending = pending.tail;
                        if (head instanceof Capture.Segment segment) {
                            frame.setObject(PENDING_SLOT, pending);
                            Resumption resumption = new Resumption(value);
                            value = null;
                            value = resume(segment, resumption, context);
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
                        // The frames it unwinds through are resumed, so they leave first.
                        SegmentList unwound = pending;
                        leavePending(frame, unwound, null);
                        pending = null;
                        throw unwind(e, unwound);
                    }
                    value = new PendingRaise(e.getRaised());
                }
            }
        } finally {
            leavePending(frame, pending, null);
        }
    }

    /**
     * The segments of {@code list} before {@code end} have joined this driver's pending ones: each is
     * a frame on the heap, which an instrument that counts frames sees enter ({@link PendingFramesNode},
     * design.md D10). Nothing, at no cost, unless such an instrument is attached.
     */
    private void enterPending(VirtualFrame frame, SegmentList list, SegmentList end) {
        ProbeNode probe = PendingFramesNode.probe(pendingFrames);
        if (probe != null) {
            for (SegmentList l = list; l != end && l != null; l = l.tail) {
                if (l.head instanceof Capture.Segment) {
                    probe.onEnter(frame);
                }
            }
        }
    }

    /**
     * The segments of {@code list} before {@code end} have left this driver's pending ones: resumed
     * (the frame then reports its own resume), dropped, or passed on to an outer prompt (whose
     * driver reports them).
     */
    private void leavePending(VirtualFrame frame, SegmentList list, SegmentList end) {
        ProbeNode probe = PendingFramesNode.probe(pendingFrames);
        if (probe != null) {
            for (SegmentList l = list; l != end && l != null; l = l.tail) {
                if (l.head instanceof Capture.Segment) {
                    probe.onReturnValue(frame, null);
                }
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
    private Object resume(Capture.Segment segment, Resumption value, SchemeContext context) {
        try {
            return resumeSegment(segment, value, context);
        } catch (AbandonedFrames e) {
            // The resumed frames got an abandoning capture, in code with tools (design.md D13).
            return e.capture;
        }
    }

    private Object resumeSegment(Capture.Segment segment, Resumption value, SchemeContext context) {
        if (segment.isOwned(context)) {
            context.inPlaceResumes++;
            if (SchemeRootNode.TORTURE) {
                // Rule 3: an owned segment is resumed in place once, and never again (owned torture,
                // SchemeRootNode.TORTURE_OWNED, does this at every non-tail call).
                segment.checkResume(true);
            }
            return resumeNode.execute(segment.root(), segment.frame(), value);
        }
        context.copiedResumes++;
        if (SchemeRootNode.TORTURE) {
            // Rule 3: resuming must never change the segment. Resume one copy, then check the
            // original against a second, untouched copy; and it was never resumed in place.
            segment.checkResume(false);
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
                throw Foreign.leaving(leaving);
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
            throw Foreign.leaving(leaving);
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
     *
     * <p>Frames of internal code (the prelude's: a {@code map} the recursion went through) are left
     * out, as stack traces leave out internal frames: a resumed frame's root is the Bytecode DSL's
     * continuation root, which is never internal (docs/upstream.md), so the trace would show them.
     * The frame outside one is then the location of the one inside it: its call of the prelude's code.
     */
    @TruffleBoundary
    private SchemeError unwind(SchemeError error, SegmentList pending) {
        List<Capture.Segment> frames = new ArrayList<>();
        for (SegmentList l = pending; l != null && frames.size() < MAX_TRACED_FRAMES; l = l.tail) {
            if (l.head instanceof Capture.Segment segment && !((RootNode) segment.root().getSourceRootNode()).isInternal()) {
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

    /**
     * Logs a spill (design.md "Stack-depth spill"): the frames that just moved to the heap, those
     * before {@code outer} in {@code pending}.
     */
    @TruffleBoundary
    private static void logSpill(SchemeContext context, SegmentList pending, SegmentList outer) {
        SchemeLogs.SPILL.fine("spill " + context.spills + ": " + SchemeLogs.frames(frames(pending, outer)) + " moved to the heap (the limit: " + context.stackLimit +
                        (context.spillDepthFixed ? " calls)" : " bytes of stack)"));
    }

    /** Logs a continuation's capture or invocation: {@code prefix}, its frames before {@code end}, {@code suffix}. */
    @TruffleBoundary
    private static void logContinuation(String prefix, SegmentList segments, SegmentList end, String suffix) {
        SchemeLogs.CONTINUATIONS.fine(prefix + SchemeLogs.frames(frames(segments, end)) + suffix);
    }

    /** The frames (segments, not prompt markers) of {@code list} before {@code end}. */
    private static int frames(SegmentList list, SegmentList end) {
        int count = 0;
        for (SegmentList l = list; l != end && l != null; l = l.tail) {
            if (l.head instanceof Capture.Segment) {
                count++;
            }
        }
        return count;
    }

    private SchemeLanguage language() {
        return getRootNode().getLanguage(SchemeLanguage.class);
    }

    /**
     * A composable continuation's frames have left the stack for this prompt (D4: a {@code guard}
     * that caught a raise): in code with tools, they end here ({@link Ending}). Without tags (no
     * tool asked for them, or {@code UNTRUSTED}'s, which are there from the start) a frame emits no
     * events, and nothing is resumed. Called only once a tool has asked for tags
     * ({@link SchemeLanguage#noTags}): else a loop of caught raises paid for the walk (its steady
     * state 6.5% slower JIT, 4% natively: design.md D13).
     */
    @TruffleBoundary
    private static void endForTools(SegmentList segments, SchemeContext context) {
        if (context.materializesTags()) {
            return;
        }
        ArrayList<Capture.Segment> tagged = null;
        for (SegmentList l = segments; l != null; l = l.tail) {
            if (l.head instanceof Capture.Segment segment && segment.root().getSourceRootNode().getBytecodeNode().getTagTree() != null) {
                if (tagged == null) {
                    tagged = new ArrayList<>();
                }
                tagged.add(segment);
            }
        }
        if (tagged != null) {
            Ending.end(tagged.toArray(new Capture.Segment[0]));
        }
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

    /**
     * Calls {@code procedure} with no arguments. Its value, or the abandoning capture whose frames
     * ended by throwing it, in code with tools ({@link AbandonedFrames}): a prompt goes on with it as
     * with a capture returned.
     */
    Object callThunk(Object procedure) {
        try {
            return calls.executeCall(procedure, new Object[]{procedure});
        } catch (AbandonedFrames e) {
            return e.capture;
        }
    }

    /**
     * Index 0 of {@code arguments} is (or is set to) the procedure. As {@link #callThunk}, an
     * {@link AbandonedFrames} is its capture.
     */
    Object callWithProcedure(Object procedure, Object[] arguments) {
        // Not a closure: a foreign executable, or the "not a procedure" error (ForeignNodes.CallNode).
        arguments[0] = procedure;
        try {
            return calls.executeCall(procedure, arguments);
        } catch (AbandonedFrames e) {
            return e.capture;
        }
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
     * A copy of a suspended frame (design.md "Copying a frame"): every slot, operand stack
     * included. A shared segment's original is never resumed, so every resume of it is independent.
     * The arguments array is shared: past its prologue a frame's holds only the closure, and nothing
     * writes it; and it makes the copy the same frame to tools ({@code SchemeRootNode.isSameFrame}).
     */
    static MaterializedFrame copy(MaterializedFrame frame) {
        FrameDescriptor descriptor = frame.getFrameDescriptor();
        MaterializedFrame copy = Truffle.getRuntime().createMaterializedFrame(frame.getArguments(), descriptor);
        frame.copyTo(0, copy, 0, descriptor.getNumberOfSlots());
        return copy;
    }

    /** Resumes a segment through a DirectCallNode cache keyed on its ContinuationRootNode (M0-02, M0-03). */
    @GenerateInline(false)
    abstract static class ResumeNode extends Node {
        abstract Object execute(ContinuationRootNode root, MaterializedFrame frame, Resumption value);

        @Specialization(guards = "root == cachedRoot", limit = "8")
        static Object doDirect(@SuppressWarnings("unused") ContinuationRootNode root, MaterializedFrame frame, Resumption value,
                        @Cached("root") ContinuationRootNode cachedRoot,
                        @Cached("create(cachedRoot.getCallTarget())") DirectCallNode callNode) {
            return callNode.call(frame, value);
        }

        @Specialization(replaces = "doDirect")
        static Object doIndirect(ContinuationRootNode root, MaterializedFrame frame, Resumption value,
                        @Cached IndirectCallNode callNode) {
            return callNode.call(root.getCallTarget(), frame, value);
        }
    }
}
