package scheme.builtins;

import java.math.BigInteger;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.Introspectable;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;

import scheme.runtime.value.SBigInteger;

/**
 * Binary arithmetic with fast paths for fixnums (overflow-checked) and flonums.
 *
 * <p>Each fixnum operation has two specializations. {@code doLong} is the fast path until the first
 * overflow: it deoptimizes on overflow, so its compiled code has no slow branch at all. The first
 * overflow replaces it, for good, with {@code doLongOverflow}, which is still a fixnum path: it
 * checks for overflow itself and builds the bignum for that one call. Without it, an overflow would
 * send every later fixnum operation to the generic path, and since a primitive has one node per
 * context, shared by every call site, one {@code (+ big big)} anywhere would slow down every
 * {@code +} in the program and the prelude.
 */
final class ArithmeticNodes {

    private ArithmeticNodes() {
    }

    /** The exact result of a fixnum operation that overflowed: never a {@code long} (design.md D1). */
    @TruffleBoundary
    static Object overflow(Arithmetic.Op op, long a, long b) {
        BigInteger x = BigInteger.valueOf(a);
        BigInteger y = BigInteger.valueOf(b);
        return SBigInteger.normalize(switch (op) {
            case ADD -> x.add(y);
            case SUB -> x.subtract(y);
            case MUL -> x.multiply(y);
            case DIV -> throw CompilerDirectives.shouldNotReachHere();
        });
    }

    @GenerateInline(false)
    @Introspectable
    abstract static class AddNode extends Node {
        abstract Object execute(Object a, Object b, int position);

        @Specialization(rewriteOn = ArithmeticException.class)
        static long doLong(long a, long b, @SuppressWarnings("unused") int position) {
            return Math.addExact(a, b);
        }

        @Specialization(replaces = "doLong")
        static Object doLongOverflow(long a, long b, @SuppressWarnings("unused") int position) {
            long r = a + b;
            // Overflow iff both operands have the sign the result lacks.
            if (CompilerDirectives.injectBranchProbability(CompilerDirectives.SLOWPATH_PROBABILITY, ((a ^ r) & (b ^ r)) < 0)) {
                return overflow(Arithmetic.Op.ADD, a, b);
            }
            return r;
        }

        @Specialization
        static double doDouble(double a, double b, @SuppressWarnings("unused") int position) {
            return a + b;
        }

        @Fallback
        static Object doGeneric(Object a, Object b, int position) {
            return Arithmetic.apply("+", Arithmetic.Op.ADD, a, b, position);
        }
    }

    @GenerateInline(false)
    @Introspectable
    abstract static class SubNode extends Node {
        abstract Object execute(Object a, Object b, int position);

        @Specialization(rewriteOn = ArithmeticException.class)
        static long doLong(long a, long b, @SuppressWarnings("unused") int position) {
            return Math.subtractExact(a, b);
        }

        @Specialization(replaces = "doLong")
        static Object doLongOverflow(long a, long b, @SuppressWarnings("unused") int position) {
            long r = a - b;
            // Overflow iff the operands' signs differ and the result's sign isn't a's.
            if (CompilerDirectives.injectBranchProbability(CompilerDirectives.SLOWPATH_PROBABILITY, ((a ^ b) & (a ^ r)) < 0)) {
                return overflow(Arithmetic.Op.SUB, a, b);
            }
            return r;
        }

        @Specialization
        static double doDouble(double a, double b, @SuppressWarnings("unused") int position) {
            return a - b;
        }

        @Fallback
        static Object doGeneric(Object a, Object b, int position) {
            return Arithmetic.apply("-", Arithmetic.Op.SUB, a, b, position);
        }
    }

    @GenerateInline(false)
    @Introspectable
    abstract static class MulNode extends Node {
        abstract Object execute(Object a, Object b, int position);

        @Specialization(rewriteOn = ArithmeticException.class)
        static long doLong(long a, long b, @SuppressWarnings("unused") int position) {
            return Math.multiplyExact(a, b);
        }

        @Specialization(replaces = "doLong")
        static Object doLongOverflow(long a, long b, @SuppressWarnings("unused") int position) {
            long r = a * b;
            // The 128-bit product fits in a long iff its high half is the sign extension of r.
            if (CompilerDirectives.injectBranchProbability(CompilerDirectives.SLOWPATH_PROBABILITY, Math.multiplyHigh(a, b) != (r >> 63))) {
                return overflow(Arithmetic.Op.MUL, a, b);
            }
            return r;
        }

        @Specialization
        static double doDouble(double a, double b, @SuppressWarnings("unused") int position) {
            return a * b;
        }

        @Fallback
        static Object doGeneric(Object a, Object b, int position) {
            return Arithmetic.apply("*", Arithmetic.Op.MUL, a, b, position);
        }
    }

    /** Signum of a - b. */
    @GenerateInline(false)
    abstract static class CompareNode extends Node {
        abstract int execute(Object a, Object b, String who, int position);

        @Specialization
        static int doLong(long a, long b, @SuppressWarnings("unused") String who, @SuppressWarnings("unused") int position) {
            return Long.compare(a, b);
        }

        @Specialization
        static int doDouble(double a, double b, @SuppressWarnings("unused") String who, @SuppressWarnings("unused") int position) {
            return Arithmetic.compareDoubles(a, b);
        }

        @Fallback
        static int doGeneric(Object a, Object b, String who, int position) {
            // a, the argument before, has been checked.
            return Arithmetic.compare(who, a, position - 1, b, position);
        }
    }
}
