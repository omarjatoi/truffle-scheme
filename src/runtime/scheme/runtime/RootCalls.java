package scheme.runtime;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.ContinuationRootNode;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;

/**
 * Calls that name the callee's root class (Truffle 25.6, GR-72833; design.md "Calls and tail
 * calls", plan.md M13-10). While the callee isn't compiled, Truffle runs its root in the caller's
 * host frame ({@code OptimizedCallTarget.callRoot}), where an untyped call goes through the call
 * target's boundary stub and its profiled entry; and a class known to the host compiler lets it call
 * the root's {@code execute} directly. A compiled callee is called as before, and partial evaluation
 * sees the same call, so compiled code doesn't change.
 *
 * <p>A procedure's root is the Bytecode DSL's {@link SchemeRootNodeGen}, and a resumed frame's its
 * continuation root; the other roots a call can reach (the primitives that are called, escape and
 * prompt procedures) are called untyped. Each root here has its call target: a procedure is made
 * from its root's call target, and a continuation root's is created before it is called.
 */
final class RootCalls {

    private RootCalls() {
    }

    /** Whether {@code target}'s root is a procedure's: a call of it names its class. */
    static boolean isProcedure(CallTarget target) {
        return target instanceof RootCallTarget t && t.getRootNode() instanceof SchemeRootNodeGen;
    }

    /**
     * Calls {@code callNode}'s target: by the root class of a procedure ({@code procedure}, from
     * {@link #isProcedure}, a constant of the call site), untyped otherwise. {@code bytecodeIndex} is
     * the caller's location, or -1 for the caller's root to find it.
     */
    static Object call(DirectCallNode callNode, boolean procedure, int bytecodeIndex, Object[] arguments) {
        if (procedure) {
            return callNode.call(SchemeRootNodeGen.class, bytecodeIndex, arguments);
        }
        return callNode.call(bytecodeIndex, arguments);
    }

    /** Calls {@code target}: by its root, cast to its class, if it is a procedure's. */
    static Object call(IndirectCallNode callNode, RootCallTarget target, int bytecodeIndex, Object[] arguments) {
        if (target.getRootNode() instanceof SchemeRootNodeGen root) {
            return callNode.call(root, bytecodeIndex, arguments);
        }
        return callNode.call(target, bytecodeIndex, arguments);
    }

    /**
     * Resumes a suspended frame through {@code callNode}, a call of its continuation root. The DSL's
     * class of continuation roots is private to {@link SchemeRootNodeGen}, so the call names the
     * class it extends, which no other root of ours does.
     */
    static Object resume(DirectCallNode callNode, Object[] arguments) {
        return callNode.call(ContinuationRootNode.class, -1, arguments);
    }

    /** Resumes a suspended frame of {@code root}, a continuation root whose call target exists. */
    static Object resume(IndirectCallNode callNode, ContinuationRootNode root, Object[] arguments) {
        return callNode.call(root, -1, arguments);
    }
}
