package scheme.runtime;

import com.oracle.truffle.api.nodes.ControlFlowException;

/**
 * An {@link Intrinsics} operation raised an error in its frame (design.md D8), and {@code raise}
 * returned a {@link Capture} (a handler invoked a continuation, captured one, or a call in it
 * spilled): the frame can't suspend in the middle of an expression, and needn't, since
 * {@code raise} never returns to it (R7RS §6.11: a handler that returns from a non-continuable
 * raise gets a secondary exception, in the handler's dynamic environment). So the operation throws
 * this, and the root returns the capture ({@link SchemeRootNode#interceptControlFlowException}), as a
 * call site that suspends does, but without a segment of the frame.
 */
final class AbandonedFrame extends ControlFlowException {

    private static final long serialVersionUID = 1L;

    final transient Capture capture;

    AbandonedFrame(Capture capture) {
        this.capture = capture;
    }
}
