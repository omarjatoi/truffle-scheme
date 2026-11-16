package scheme.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

import scheme.runtime.value.SClosure;

/**
 * The root of a primitive procedure. Hand-written rather than a Bytecode DSL root: it only checks the
 * arity and delegates to a {@link PrimitiveNode}, which has no bytecode to interpret. Most calls of
 * a primitive don't reach it: call sites run a node of their own instead (plan.md M5-09). It runs
 * the calls that aren't run in place: from a site that has seen too many procedures, a trampoline
 * or a prompt, and the calls of primitives that transfer control.
 */
public final class PrimitiveRootNode extends RootNode {

    private static final Object[] NO_CAPTURES = new Object[0];

    private final PrimitiveSpec spec;
    @Child private PrimitiveNode body;

    public PrimitiveRootNode(SchemeLanguage language, PrimitiveSpec spec) {
        super(language);
        this.spec = spec;
        this.body = spec.factory().get();
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] args = frame.getArguments();
        spec.checkArity(args.length - 1);
        return body.invoke(args);
    }

    /**
     * A new primitive procedure: one that a primitive makes at run time, such as a record type's
     * accessor (plan.md M7-04). Not from compiled code: it creates a root node.
     */
    @TruffleBoundary
    public static SClosure procedure(SchemeLanguage language, PrimitiveSpec spec) {
        return new SClosure(new PrimitiveRootNode(language, spec).getCallTarget(), NO_CAPTURES);
    }

    /**
     * The spec of {@code target}'s primitive if call sites run it in place (it only returns values),
     * else null.
     */
    static PrimitiveSpec inPlace(RootCallTarget target) {
        return target.getRootNode() instanceof PrimitiveRootNode root && root.spec.returnsValue() ? root.spec : null;
    }

    /** A new node for {@code spec}'s primitive, for a call site of its own; null if there is no spec. */
    static PrimitiveNode createNode(PrimitiveSpec spec) {
        return spec == null ? null : spec.factory().get();
    }

    /** A primitive that only returns values is a leaf: inlined in every tier when it is called. */
    @Override
    public boolean isTrivial() {
        return spec.returnsValue();
    }

    @Override
    public String getName() {
        return spec.name();
    }

    @Override
    public String toString() {
        return spec.name();
    }
}
