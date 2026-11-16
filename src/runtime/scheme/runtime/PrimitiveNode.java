package scheme.runtime;

import com.oracle.truffle.api.nodes.Node;

/**
 * The implementation of a primitive procedure. It gets the arguments as a call target does
 * (design.md "Calls and tail calls"): {@code args[0]} is the procedure itself and the arguments
 * follow, so that no call copies them. A call site may run a copy of the node of its own, without a
 * Truffle call (plan.md M5-09); otherwise the primitive's {@link PrimitiveRootNode} runs it.
 */
public abstract class PrimitiveNode extends Node {

    /** Runs the primitive on {@code args[1..]}; {@code args[0]} is the procedure. */
    public abstract Object invoke(Object[] args);
}
