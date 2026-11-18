package scheme.runtime;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.Introspectable;
import com.oracle.truffle.api.dsl.NeverDefault;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RepeatingNode;

import scheme.runtime.value.SClosure;

/**
 * The trampoline of a non-tail call site or a prompt (design.md "Calls and tail calls"): performs
 * the {@link TailCall}s that come back, until a value does (plan.md M5-10). Each call site has its
 * own, with inline caches of {@link DirectCallNode}s keyed on the tail-called procedure's call
 * target, so a procedure reached by a tail call can be inlined into the site, and its
 * {@code TailCall} and argument array can be scalar-replaced. A primitive that only returns values
 * runs in place instead, with a node of the cache's own (plan.md M10-03). Past {@link #CACHE_LIMIT}
 * targets ({@link #REST_CACHE_LIMIT} for the tail calls after the first) a cache calls indirectly.
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

    /** As many tail-called targets as the first tail call's cache holds before it calls indirectly. */
    static final int CACHE_LIMIT = 4;
    /** As many as the cache of the tail calls after the first holds (plan.md M8-05). */
    static final int REST_CACHE_LIMIT = 8;

    /**
     * The key of the auxiliary frame slot that holds the next tail call while {@link #loop} runs. A
     * root's trampolines share it: only one of them runs at a time in a frame, since each runs in a
     * call that the frame waits for.
     */
    private static final Object NEXT_TAIL_CALL = new Object();

    /** Performs the first tail call that comes back; reports polymorphism when megamorphic. */
    @Child private TailCalls first = TrampolineFactory.TailCallsNodeGen.create(true, CACHE_LIMIT);
    /**
     * Performs the tail calls after the first ({@link TailCallLoop}), and the calls of
     * {@link #executeCall}: a Truffle loop, so that a long one in a procedure that runs once is
     * compiled by on-stack replacement.
     */
    @Child private LoopNode loop = Truffle.getRuntime().createLoopNode(new TailCallLoop());

    /**
     * Whether a call from this non-tail call site has come back other than by a spill
     * ({@link #leaveDescent}, design.md "Stack-depth spill", plan.md M9-01): until then, nothing
     * after the call has run, and code compiled from it would deoptimize where the call returns. Only the interpreter writes it; the compiler threads read it
     * ({@link #blocksCompilation}).
     */
    private volatile boolean returned;
    /** The interpreted activations that wait in a call from this site, while it hasn't returned. */
    private volatile int descending;
    private static final AtomicIntegerFieldUpdater<Trampoline> DESCENDING = AtomicIntegerFieldUpdater.newUpdater(Trampoline.class, "descending");
    /**
     * Until when ({@link System#nanoTime}) a spill of a first descent holds off the compilation of
     * the procedure this site is in: its frames have left the Java stack, but the descent goes on
     * from the frame that spilled, on a fresh one.
     */
    private volatile long heldUntil;
    /** Whether this site is in its root's list of sites in a first descent. */
    private boolean registered;

    private Trampoline() {
    }

    /**
     * Whether a call from this site that is about to be made is part of a first descent: the
     * interpreter is making it, and no call from this site has returned yet. If so, the caller
     * calls {@link #enterDescent} before the call and {@link #leaveDescent} after it, however it
     * ends.
     */
    boolean inFirstDescent() {
        return CompilerDirectives.inInterpreter() && !returned;
    }

    void enterDescent(SchemeRootNode root) {
        if (DESCENDING.incrementAndGet(this) == SchemeRootNode.FIRST_DESCENT_DEPTH && !registered) {
            registered = true;
            root.registerFirstDescent(this);
        }
    }

    /**
     * The call is over: it came back with {@code result}, or null if an exception left it. Anything
     * but a spill's {@link Capture} means the site has returned: a value; another capture, whose
     * frames are resumed past the call (in their continuations, which share this code) or
     * abandoned; an exception. A site whose calls never come back with a value ({@code fibc}'s
     * {@code call/cc}, whose {@code k} is always invoked) mustn't hold its procedure off for good.
     * A spill's frames go on with the descent instead: they leave the Java stack, but the descent
     * goes on from the frame that spilled, and its next frames would otherwise be compiled, so the
     * spill holds compilation off for a while.
     */
    void leaveDescent(Object result) {
        if (!(result instanceof Capture capture && capture.kind == Capture.Kind.SPILL)) {
            returned = true;
        } else if (descending >= SchemeRootNode.FIRST_DESCENT_DEPTH) {
            // The frames nearest the root prompt, fewer than that, leave the hold as it is.
            heldUntil = System.nanoTime() + SchemeRootNode.FIRST_DESCENT_HOLD_NANOS;
        }
        DESCENDING.decrementAndGet(this);
    }

    /**
     * Whether this site holds off the compilation of the procedure it is in: no call from it has
     * returned yet, and many interpreted frames wait in one, or a spill of them has just happened.
     */
    boolean blocksCompilation(long now) {
        return !returned && (descending >= SchemeRootNode.FIRST_DESCENT_DEPTH || now - heldUntil < 0);
    }

    /** Whether this site can leave its root's list: a call from it has returned. */
    boolean firstDescentOver() {
        return returned;
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

    private TailCallLoop body() {
        return (TailCallLoop) loop.getRepeatingNode();
    }

    /**
     * Performs {@code value} if it is a tail call, and every tail call after it; returns the value.
     * {@code frame} is the frame of the root this site is in, which holds the loop's state.
     *
     * <p>The tail calls count as iterations of a loop of the root this site is in (plan.md M7-02),
     * as a self tail call's jump does: a procedure called a few times whose call sites perform many
     * tail calls (a loop through several procedures) gets compiled, and its loop with it. The ones
     * after the first are a Truffle loop ({@link LoopNode}), so a long loop in a procedure that runs
     * once (a benchmark's harness loop, which performs every tail call of a CPS program) is compiled
     * by on-stack replacement (design.md "Calls and tail calls"). As a Java loop it ran in this
     * method's host-compiled code, at whatever speed HotSpot had compiled it to.
     */
    public Object run(VirtualFrame frame, Object value) {
        if (!(value instanceof TailCall tail)) {
            return value;
        }
        // The first tail call is peeled out of the loop, so a TailCall built by a callee inlined
        // into this site never flows into the loop's phi and can stay virtual.
        Object result = first.execute(tail.procedure, tail.arguments);
        LoopNode.reportLoopCount(this, 1);
        if (!(result instanceof TailCall)) {
            return result;
        }
        frame.setAuxiliarySlot(body().slot(frame), result);
        // Only the frame holds it now, and the loop clears it as it makes the call: this method's
        // Java frame, which an interpreter keeps for the whole loop, mustn't keep its arguments
        // alive (design.md "Tail calls keep nothing alive").
        result = null;
        return loop.execute(frame);
    }

    /**
     * {@link #run(VirtualFrame, Object)} without a frame, for the calls a site makes besides its
     * own ({@code raise} for an error): a Java loop, which isn't compiled by OSR.
     */
    public Object run(Object value) {
        if (!(value instanceof TailCall tail)) {
            return value;
        }
        Object result = first.execute(tail.procedure, tail.arguments);
        TailCalls rest = body().rest;
        while (result instanceof TailCall next) {
            result = rest.execute(next.procedure, next.arguments);
        }
        return result;
    }

    /**
     * Calls {@code procedure} with {@code arguments}, whose index 0 is the procedure: a call that
     * isn't a tail call of the site's callee (a prompt's, or {@code raise} for an error).
     */
    public Object executeCall(Object procedure, Object[] arguments) {
        return body().rest.execute(procedure, arguments);
    }

    /**
     * The body of a trampoline's loop: performs the tail call in the frame's auxiliary slot, and
     * continues while another comes back. The state is in the frame, not in this node: the same
     * site can run in several activations at once (recursion, threads), and when an OSR-compiled
     * loop deoptimizes, the interpreter goes on from the frame.
     */
    static final class TailCallLoop extends Node implements RepeatingNode {

        /** Performs the tail calls after the first, and the calls of {@link #executeCall}. */
        @Child TailCalls rest = TrampolineFactory.TailCallsNodeGen.create(false, REST_CACHE_LIMIT);

        /** The auxiliary slot of the frames this loop runs in; null before the first loop. */
        @CompilationFinal private AuxiliarySlot slot;

        /** An auxiliary slot of a frame descriptor's frames; published in one write. */
        private record AuxiliarySlot(FrameDescriptor descriptor, int index) {
        }

        /** The index of the auxiliary slot that holds the next tail call in frames like {@code frame}. */
        int slot(VirtualFrame frame) {
            FrameDescriptor descriptor = frame.getFrameDescriptor();
            AuxiliarySlot cached = slot;
            if (cached == null || cached.descriptor != descriptor) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                cached = new AuxiliarySlot(descriptor, descriptor.findOrAddAuxiliarySlot(NEXT_TAIL_CALL));
                slot = cached;
            }
            return cached.index;
        }

        @Override
        public Object executeRepeatingWithValue(VirtualFrame frame) {
            int index = slot(frame);
            TailCall next = (TailCall) frame.getAuxiliarySlot(index);
            // Cleared before the call: the frame mustn't keep the tail call's arguments alive while
            // the call runs, nor after the loop, however it ends (design.md "Tail calls keep
            // nothing alive").
            frame.setAuxiliarySlot(index, null);
            Object result = rest.execute(next.procedure, next.arguments);
            if (result instanceof TailCall) {
                frame.setAuxiliarySlot(index, result);
                return CONTINUE_LOOP_STATUS;
            }
            return result;
        }

        @Override
        public boolean executeRepeating(VirtualFrame frame) {
            throw CompilerDirectives.shouldNotReachHere("the loop runs executeRepeatingWithValue");
        }

        @Override
        public String toString() {
            return "tail calls";
        }
    }

    /** One inline cache of tail-called procedures. Introspectable for StableWarmupTest. */
    @GenerateInline(false)
    @Introspectable
    abstract static class TailCalls extends Node {

        /** Whether this cache reports polymorphism when it goes megamorphic. */
        private final boolean reports;
        /** How many targets it holds before it calls indirectly. */
        final int limit;

        TailCalls(boolean reports, int limit) {
            this.reports = reports;
            this.limit = limit;
        }

        abstract Object execute(Object procedure, Object[] arguments);

        @NeverDefault
        IndirectCallNode megamorphic() {
            if (reports) {
                reportPolymorphicSpecialize();
            }
            return IndirectCallNode.create();
        }

        /**
         * Calls the cached target. A primitive that only returns values runs in place instead, as at
         * a call site (design.md "Primitives run in place"): with a node of this entry's own, and
         * no Truffle call of its root, which compiled code would have to inline or not by the
         * inlining budget (plan.md M10-03). Its errors leave the trampoline for the call site, as
         * from its root. One specialization for both, so that a tail call of a closure checks no
         * more entries than before: each entry's {@code primitive} is a constant, and partial
         * evaluation keeps one of the branches.
         */
        @Specialization(guards = "procedure.callTarget == cachedTarget", limit = "limit")
        static Object doDirect(@SuppressWarnings("unused") SClosure procedure, Object[] arguments,
                        @Bind Node node,
                        @Cached("procedure.callTarget") RootCallTarget cachedTarget,
                        @Cached("inPlace(cachedTarget)") PrimitiveSpec spec,
                        @Cached("createNode(spec)") PrimitiveNode primitive,
                        @Cached("createCall(cachedTarget, spec)") DirectCallNode callNode) {
            if (primitive != null) {
                spec.checkArity(arguments.length - 1);
                return SchemeRootNode.inPlaceResult(primitive.invoke(arguments));
            }
            // The callee's prologue releases it (SchemeRootNode.releaseArguments).
            SchemeRootNode.handOffArguments(SchemeContext.get(node), arguments);
            return callNode.call(arguments);
        }

        @Specialization(replaces = "doDirect")
        static Object doIndirect(SClosure procedure, Object[] arguments,
                        @Bind Node node,
                        @Cached("megamorphic()") IndirectCallNode callNode) {
            SchemeRootNode.handOffArguments(SchemeContext.get(node), arguments);
            return callNode.call(procedure.callTarget, arguments);
        }

        /** A foreign executable is a procedure (design.md D20); anything else is not. */
        @Fallback
        static Object doOther(Object procedure, Object[] arguments) {
            return Foreign.call(procedure, arguments);
        }

        static PrimitiveSpec inPlace(RootCallTarget target) {
            return PrimitiveRootNode.inPlace(target);
        }

        static PrimitiveNode createNode(PrimitiveSpec spec) {
            return PrimitiveRootNode.createNode(spec);
        }

        /** The call node of a target whose primitive doesn't run in place; null if it does. */
        static DirectCallNode createCall(RootCallTarget target, PrimitiveSpec spec) {
            return spec == null ? DirectCallNode.create(target) : null;
        }
    }
}
