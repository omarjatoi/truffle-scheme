package scheme.runtime;

import com.oracle.truffle.api.nodes.ControlFlowException;

/**
 * Thrown by invoking a continuation: unwinds the Java stack to the prompt, which abandons its
 * current continuation and resumes this one with {@link #value}.
 */
public final class InvokeContinuation extends ControlFlowException {

    private static final long serialVersionUID = 1L;

    public final transient Continuation continuation;
    public final transient Object value;

    public InvokeContinuation(Continuation continuation, Object value) {
        this.continuation = continuation;
        this.value = value;
    }
}
