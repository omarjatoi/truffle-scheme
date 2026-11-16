package scheme.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.NeverDefault;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;

import scheme.runtime.value.SClosure;

/**
 * The trampoline of a non-tail call site or a prompt (design.md "Calls and tail calls"): performs
 * the {@link TailCall}s that come back, until a value does (plan.md M5-10). Each call site has its
 * own, with an inline cache of {@link DirectCallNode}s keyed on the tail-called procedure's call
 * target, so a procedure reached by a tail call can be inlined into the site, and its
 * {@code TailCall} and argument array can be scalar-replaced. Past {@link #CACHE_LIMIT} targets the
 * site calls indirectly.
 *
 * <p>It reports polymorphism, as {@code Call} does: a site that sees several tail-called procedures
 * asks Truffle to split the procedure it is in for each of its callers.
 *
 * <p>Tail calls stay in constant space: each one returns to this loop before the next is made, so
 * the Java stack depth doesn't depend on how many there are.
 */
@GenerateInline(false)
@ReportPolymorphism
public abstract class Trampoline extends Node {

    /** As many tail-called targets as a site caches before it calls indirectly. */
    static final int CACHE_LIMIT = 4;

    /** The first procedure the enclosing {@code Call} site cached, or null (see noteCallee). */
    private RootCallTarget firstCallee;

    @NeverDefault
    public static Trampoline create() {
        return TrampolineNodeGen.create();
    }

    /**
     * Called by the enclosing {@code Call} site, when it specializes, with each call target it
     * caches: from the second distinct one on, the site is polymorphic, and reports it (as
     * {@code @ReportPolymorphism} would), so Truffle can split the procedure it is in. Returns the
     * target.
     */
    RootCallTarget noteCallee(RootCallTarget target) {
        if (firstCallee == null) {
            firstCallee = target;
        } else if (target != firstCallee) {
            reportPolymorphicSpecialize();
        }
        return target;
    }

    /** Called when the enclosing {@code Call} site goes megamorphic: reports it, and makes its call node. */
    @NeverDefault
    IndirectCallNode megamorphic() {
        reportPolymorphicSpecialize();
        return IndirectCallNode.create();
    }

    /** Performs {@code first} if it is a tail call, and every tail call after it; returns the value. */
    public final Object run(Object first) {
        if (!(first instanceof TailCall tail)) {
            return first;
        }
        // The first tail call is peeled out of the loop, so a TailCall built by a callee inlined
        // into this site never flows into the loop's phi and can stay virtual.
        Object result = executeCall(tail.procedure, tail.arguments);
        while (result instanceof TailCall next) {
            result = executeCall(next.procedure, next.arguments);
        }
        return result;
    }

    /** Calls {@code procedure} with {@code arguments}, whose index 0 is the procedure. */
    public abstract Object executeCall(Object procedure, Object[] arguments);

    @Specialization(guards = "procedure.callTarget == cachedTarget", limit = "CACHE_LIMIT")
    static Object doDirect(@SuppressWarnings("unused") SClosure procedure, Object[] arguments,
                    @Cached("procedure.callTarget") @SuppressWarnings("unused") RootCallTarget cachedTarget,
                    @Cached("create(cachedTarget)") DirectCallNode callNode) {
        return callNode.call(arguments);
    }

    @Specialization(replaces = "doDirect")
    static Object doIndirect(SClosure procedure, Object[] arguments,
                    @Cached IndirectCallNode callNode) {
        return callNode.call(procedure.callTarget, arguments);
    }

    @Fallback
    static Object doNotAProcedure(Object procedure, @SuppressWarnings("unused") Object[] arguments) {
        throw SchemeRootNode.notAProcedure(procedure);
    }
}
