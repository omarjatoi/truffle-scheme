package scheme.runtime;

import com.oracle.truffle.api.nodes.ControlFlowException;

/**
 * Thrown by invoking an escape-only continuation (M5-04, {@link EscapeRootNode}): unwinds the Java
 * stack to the {@code %call/ec} prompt whose tag is {@link #target}, which returns {@link #value}.
 * If a capture has flattened that prompt into a marker, the prompt driver that holds the marker
 * catches it instead and drops the pending segments up to the marker.
 */
public final class EscapeException extends ControlFlowException {

    private static final long serialVersionUID = 1L;

    public final transient EscapeRootNode.Target target;
    public final transient Object value;

    public EscapeException(EscapeRootNode.Target target, Object value) {
        this.target = target;
        this.value = value;
    }
}
