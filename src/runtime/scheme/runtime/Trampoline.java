package scheme.runtime;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.Introspectable;
import com.oracle.truffle.api.dsl.NeverDefault;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;

import scheme.runtime.value.SClosure;

/**
 * The trampoline of a non-tail call site or a prompt (design.md "Calls and tail calls"): performs
 * the {@link TailCall}s that come back, until a value does (plan.md M5-10). Each call site has its
 * own, with inline caches of {@link DirectCallNode}s keyed on the tail-called procedure's call
 * target, so a procedure reached by a tail call can be inlined into the site, and its
 * {@code TailCall} and argument array can be scalar-replaced. Past {@link #CACHE_LIMIT} targets a
 * cache calls indirectly.
 *
 * <p>There are two caches (plan.md M7-01): one for the first tail call that comes back, and one for
 * the tail calls after it. A procedure's tail calls form a chain ({@code map} tail-calls its
 * {@code loop}, which tail-calls {@code reverse}, which tail-calls its own loop). With one cache,
 * every link of the chain was compiled, and could be inlined, both where the first tail call is
 * performed and in the loop that performs the rest: twice the code for one chain, in every site
 * that calls {@code map}. And a chain whose later links vary made the site look polymorphic, so
 * Truffle split the procedure it is in for each of its callers, for nothing: the site calls the same
 * procedure whoever its caller is.
 *
 * <p>Polymorphism is reported (so that Truffle splits the procedure the site is in, for each of its
 * callers) only when a cache goes megamorphic: the site's own {@code Call} cache, or the cache of
 * the first tail call, which can depend on the site's caller (a procedure passed down and
 * tail-called). Up to {@link #CACHE_LIMIT} procedures, the cache inlines each of them, and a split
 * would only make the site cheaper at the price of another copy to compile: copies that, on CI's
 * runners, kept the compiler threads busy past the point where the benchmarks measure (M7-01).
 *
 * <p>Tail calls stay in constant space: each one returns to this loop before the next is made, so
 * the Java stack depth doesn't depend on how many there are.
 */
public final class Trampoline extends Node {

    /** As many tail-called targets as a cache holds before it calls indirectly. */
    static final int CACHE_LIMIT = 4;

    /** Performs the first tail call that comes back; reports polymorphism when megamorphic. */
    @Child private TailCalls first = TrampolineFactory.TailCallsNodeGen.create(true);
    /** Performs the tail calls after the first, and the calls of {@link #executeCall}. */
    @Child private TailCalls rest = TrampolineFactory.TailCallsNodeGen.create(false);

    private Trampoline() {
    }

    @NeverDefault
    public static Trampoline create() {
        return new Trampoline();
    }

    /**
     * Called when the enclosing {@code Call} site goes megamorphic: reports it, so Truffle can split
     * the procedure the site is in for each of its callers, and makes the site's call node. (A
     * workaround for a Truffle 25.5 bug: {@code scheme.test.upstream.UpstreamReportPolymorphismTest},
     * docs/upstream.md#reportpolymorphism-on-an-operation.)
     */
    @NeverDefault
    IndirectCallNode megamorphic() {
        reportPolymorphicSpecialize();
        return IndirectCallNode.create();
    }

    /**
     * Performs {@code value} if it is a tail call, and every tail call after it; returns the value.
     * The tail calls count as iterations of a loop of the root this site is in (plan.md M7-02), as
     * a self tail call's jump does: a procedure called a few times whose call sites perform many tail
     * calls (a loop through several procedures) gets compiled, and its loop with it.
     */
    public Object run(Object value) {
        if (!(value instanceof TailCall tail)) {
            return value;
        }
        // The first tail call is peeled out of the loop, so a TailCall built by a callee inlined
        // into this site never flows into the loop's phi and can stay virtual.
        Object result = first.execute(tail.procedure, tail.arguments);
        int count = 1;
        while (result instanceof TailCall next) {
            result = rest.execute(next.procedure, next.arguments);
            count++;
        }
        LoopNode.reportLoopCount(this, count);
        return result;
    }

    /**
     * Calls {@code procedure} with {@code arguments}, whose index 0 is the procedure: a call that
     * isn't a tail call of the site's callee (a prompt's, or {@code raise} for an error).
     */
    public Object executeCall(Object procedure, Object[] arguments) {
        return rest.execute(procedure, arguments);
    }

    /** One inline cache of tail-called procedures. Introspectable for StableWarmupTest. */
    @GenerateInline(false)
    @Introspectable
    abstract static class TailCalls extends Node {

        static final int LIMIT = CACHE_LIMIT;

        /** Whether this cache reports polymorphism when it goes megamorphic. */
        private final boolean reports;

        TailCalls(boolean reports) {
            this.reports = reports;
        }

        abstract Object execute(Object procedure, Object[] arguments);

        @NeverDefault
        IndirectCallNode megamorphic() {
            if (reports) {
                reportPolymorphicSpecialize();
            }
            return IndirectCallNode.create();
        }

        @Specialization(guards = "procedure.callTarget == cachedTarget", limit = "LIMIT")
        static Object doDirect(@SuppressWarnings("unused") SClosure procedure, Object[] arguments,
                        @Cached("procedure.callTarget") @SuppressWarnings("unused") RootCallTarget cachedTarget,
                        @Cached("create(cachedTarget)") DirectCallNode callNode) {
            return callNode.call(arguments);
        }

        @Specialization(replaces = "doDirect")
        static Object doIndirect(SClosure procedure, Object[] arguments,
                        @Cached("megamorphic()") IndirectCallNode callNode) {
            return callNode.call(procedure.callTarget, arguments);
        }

        @Fallback
        static Object doNotAProcedure(Object procedure, @SuppressWarnings("unused") Object[] arguments) {
            throw SchemeRootNode.notAProcedure(procedure);
        }
    }
}
