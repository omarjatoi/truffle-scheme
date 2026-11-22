package scheme.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.nodes.ControlFlowException;

/**
 * An abandoning capture (APPLY, ESCAPE: a continuation's invocation) leaving the frames it abandons
 * as an exception, in code that tools instrument (design.md D13, "Ending an escaped frame for
 * tools"). Uninstrumented, such a frame suspends into the capture, which drops it
 * ({@link Capture#addSegment}): a Bytecode DSL yield, which a debugger takes for a generator's
 * suspension, so a step that started in the frame waits for a resumption that never comes. Thrown
 * instead, it ends the frame exceptionally for every tag around it, so a step goes on at the next
 * statement that runs.
 *
 * <p>One of the Bytecode DSL's unwind exceptions ({@code GenerateBytecode.unwindExceptions} on
 * {@link SchemeRootNode}): a control-flow exception that the generated interpreter passes to the
 * tags, and to nothing else (no interception hook, no guest handler). Other control-flow
 * exceptions reach no tag. Being one, it is no guest exception: an exception breakpoint ignores it.
 *
 * <p>Every frame between the call site that got the capture and its prompt is abandoned, so the
 * exception goes through them all, and the prompt's driver catches it and goes on with the capture
 * ({@link PromptDriver}, {@link PromptRootNode}), as if the frame below it had returned it.
 */
final class AbandonedFrames extends ControlFlowException {

    private static final long serialVersionUID = 1L;

    final transient Capture capture;

    private AbandonedFrames(Capture capture) {
        this.capture = capture;
    }

    /**
     * Whether a frame of {@code bytecode} that got {@code capture} at a call site ends by throwing
     * it ({@link #of}) instead of suspending: an abandoning capture, in bytecode with tags (a tool
     * asked for them), except under {@code UNTRUSTED}, whose bytecode has every tag from the start
     * for the sandbox's limits (design.md D10), and whose isolate has no debugger.
     */
    static boolean unwinds(Capture capture, BytecodeNode bytecode, SchemeContext context) {
        return capture.abandons() && bytecode.getTagTree() != null && !context.materializesTags();
    }

    @TruffleBoundary
    static AbandonedFrames of(Capture capture) {
        return new AbandonedFrames(capture);
    }

    /** What the frames a guard's capture left end with, for tools: no capture ({@link Ending}). */
    @TruffleBoundary
    static AbandonedFrames ending() {
        return new AbandonedFrames(null);
    }
}
