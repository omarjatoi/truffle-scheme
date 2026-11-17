package scheme.runtime;

import java.util.Arrays;
import java.util.concurrent.locks.Lock;

import com.oracle.truffle.api.CompilerDirectives;
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
 *
 * <p>It doesn't say it is trivial ({@link RootNode#isTrivial()}): many primitives loop, over their
 * arguments ({@code +}, {@code vector}) or their data ({@code string<?}), and the compiler prints a
 * performance warning on the program's standard error each time it inlines a root that says it is
 * trivial but has a loop after partial evaluation. Calls of primitives through this root, such as
 * {@code apply}'s, are inlined by the usual policy instead.
 */
public final class PrimitiveRootNode extends RootNode {

    private static final Object[] NO_CAPTURES = new Object[0];

    private final PrimitiveSpec spec;
    /**
     * Made on the first call, not with the root: a context makes a root for each of the ~280
     * primitives as it starts, and most are never called (design.md D17).
     */
    @Child private PrimitiveNode body;

    public PrimitiveRootNode(SchemeLanguage language, PrimitiveSpec spec) {
        super(language);
        this.spec = spec;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] args = frame.getArguments();
        spec.checkArity(args.length - 1);
        PrimitiveNode node = body;
        if (node == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            node = createBody();
        }
        Object result = node.invoke(args);
        if (!spec.returnsValue() && result instanceof TailCall tail && tail.arguments != args) {
            // apply's list, say: the caller may hold the array while the tail call it asks for
            // runs, which may be for ever (R7RS §3.5, design.md "Tail calls keep nothing alive").
            if (CompilerDirectives.inCompiledCode()) {
                SchemeRootNode.releaseArguments(SchemeContext.get(this), args);
            }
            Arrays.fill(args, 1, args.length, null);
        }
        return result;
    }

    private PrimitiveNode createBody() {
        Lock lock = getLock();
        lock.lock();
        try {
            if (body == null) {
                body = insert(spec.createNode());
            }
            return body;
        } finally {
            lock.unlock();
        }
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
        return spec == null ? null : spec.createNode();
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
