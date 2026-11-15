package scheme.runtime;

import com.oracle.truffle.api.nodes.Node;

/** The implementation of a primitive procedure. Gets the arguments without the procedure itself. */
public abstract class PrimitiveNode extends Node {

    public abstract Object execute(Object[] arguments);
}
