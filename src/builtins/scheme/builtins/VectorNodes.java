package scheme.builtins;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.Introspectable;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;

import scheme.runtime.SchemeError;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SVector;

/**
 * {@code vector-ref} and {@code vector-set!}, specialized on the vector's storage strategy (plan.md
 * M5-06, {@link SVector}): one specialization per kind of storage, so a site that only ever sees
 * {@code double[]} vectors compiles to an unboxed array access.
 *
 * <p>No specialization replaces another. A call site runs a node of its own (plan.md M5-09), but the
 * primitive's root node, which every indirect call (through {@code apply}, a procedure passed as an
 * argument, ...) shares, has one too, so a {@code replaces} would let one odd vector slow down
 * every access made that way. The specializations' guards are disjoint: a store that
 * doesn't fit the vector's storage goes to {@code doGeneralize}, which converts the storage once and
 * stores; later stores into that vector go to {@code doObjects}.
 */
final class VectorNodes {

    private VectorNodes() {
    }

    /**
     * The index {@code k} as an {@code int}, checked against {@code length}: the storage array's
     * length rather than {@link SVector#length}, so the compiler can drop the array access's own
     * bounds check.
     */
    static int index(String who, SVector v, int length, long k) {
        if (CompilerDirectives.injectBranchProbability(CompilerDirectives.SLOWPATH_PROBABILITY, k < 0 || k >= length)) {
            throw badIndex(who, v, k);
        }
        return (int) k;
    }

    @TruffleBoundary
    static SchemeError badIndex(String who, SVector v, Object k) {
        return ControlPrimitives.notA(who, "valid index for a vector of length " + v.length, k);
    }

    @TruffleBoundary
    static SchemeError immutable(String who) {
        return new SchemeError(who + ": vector is immutable (a literal)");
    }

    /** The error for arguments no specialization takes: a non-vector, or a non-fixnum index. */
    static SchemeError badArguments(String who, Object v, Object k) {
        if (v instanceof SVector vector) {
            return badIndex(who, vector, k);
        }
        return ControlPrimitives.notA(who, "vector", v);
    }

    @GenerateInline(false)
    @Introspectable
    abstract static class VectorRefNode extends Node {
        abstract Object execute(Object v, Object k);

        @Specialization(guards = "v.isLongs()")
        static long doLongs(SVector v, long k) {
            long[] a = v.longs();
            return a[index("vector-ref", v, a.length, k)];
        }

        @Specialization(guards = "v.isDoubles()")
        static double doDoubles(SVector v, long k) {
            double[] a = v.doubles();
            return a[index("vector-ref", v, a.length, k)];
        }

        @Specialization(guards = "v.isObjects()")
        static Object doObjects(SVector v, long k) {
            Object[] a = v.objects();
            return a[index("vector-ref", v, a.length, k)];
        }

        @Fallback
        static Object doError(Object v, Object k) {
            throw badArguments("vector-ref", v, k);
        }
    }

    @GenerateInline(false)
    @Introspectable
    abstract static class VectorSetNode extends Node {
        abstract Object execute(Object v, Object k, Object value);

        @Specialization(guards = "v.isLongs()")
        static Object doLongs(SVector v, long k, long value) {
            long[] a = v.longs();
            int i = index("vector-set!", v, a.length, k);
            if (v.immutable) {
                throw immutable("vector-set!");
            }
            a[i] = value;
            return SConstant.UNSPECIFIED;
        }

        @Specialization(guards = "v.isDoubles()")
        static Object doDoubles(SVector v, long k, double value) {
            double[] a = v.doubles();
            int i = index("vector-set!", v, a.length, k);
            if (v.immutable) {
                throw immutable("vector-set!");
            }
            a[i] = value;
            return SConstant.UNSPECIFIED;
        }

        @Specialization(guards = "v.isObjects()")
        static Object doObjects(SVector v, long k, Object value) {
            Object[] a = v.objects();
            int i = index("vector-set!", v, a.length, k);
            if (v.immutable) {
                throw immutable("vector-set!");
            }
            a[i] = value;
            return SConstant.UNSPECIFIED;
        }

        /** A store that doesn't fit a {@code long[]} or {@code double[]}: generalizes the storage. */
        @Specialization(guards = "!v.fits(value)")
        static Object doGeneralize(SVector v, long k, Object value) {
            int i = index("vector-set!", v, v.length, k);
            if (v.immutable) {
                throw immutable("vector-set!");
            }
            v.generalize()[i] = value;
            return SConstant.UNSPECIFIED;
        }

        @Fallback
        static Object doError(Object v, Object k, @SuppressWarnings("unused") Object value) {
            throw badArguments("vector-set!", v, k);
        }
    }
}
