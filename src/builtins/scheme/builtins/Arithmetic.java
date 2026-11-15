package scheme.builtins;

import java.math.BigInteger;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.runtime.SchemeError;
import scheme.runtime.printer.Printer;
import scheme.runtime.value.SBigInteger;

/**
 * The generic (slow) path of arithmetic on exact integers ({@code long}, {@link SBigInteger}) and
 * flonums. Fixnum results overflow into SBigInteger and never wrap; big results that fit in a
 * {@code long} are demoted (AGENTS.md "Truffle conventions"). Rationals and complex numbers arrive
 * with the numeric tower (M4).
 */
final class Arithmetic {

    private Arithmetic() {
    }

    enum Op {
        ADD,
        SUB,
        MUL
    }

    @TruffleBoundary
    static Object check(String who, Object x) {
        if (x instanceof Long || x instanceof Double || x instanceof SBigInteger) {
            return x;
        }
        throw new SchemeError(who + ": not a number (or not supported until M4): " + Printer.print(x, Printer.Mode.WRITE));
    }

    private static BigInteger big(Object x) {
        return x instanceof Long l ? BigInteger.valueOf(l) : ((SBigInteger) x).value;
    }

    private static double flo(Object x) {
        return x instanceof Double d ? d : x instanceof Long l ? (double) l : ((SBigInteger) x).value.doubleValue();
    }

    @TruffleBoundary
    static Object apply(String who, Op op, Object a, Object b) {
        check(who, a);
        check(who, b);
        if (a instanceof Double || b instanceof Double) {
            double x = flo(a);
            double y = flo(b);
            return switch (op) {
                case ADD -> x + y;
                case SUB -> x - y;
                case MUL -> x * y;
            };
        }
        BigInteger x = big(a);
        BigInteger y = big(b);
        return SBigInteger.normalize(switch (op) {
            case ADD -> x.add(y);
            case SUB -> x.subtract(y);
            case MUL -> x.multiply(y);
        });
    }

    /** -1, 0 or 1. */
    @TruffleBoundary
    static int compare(String who, Object a, Object b) {
        check(who, a);
        check(who, b);
        if (a instanceof Double || b instanceof Double) {
            return Double.compare(flo(a), flo(b));
        }
        return big(a).compareTo(big(b));
    }
}
