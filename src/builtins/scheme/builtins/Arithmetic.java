package scheme.builtins;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.runtime.SchemeError;
import scheme.runtime.printer.Printer;
import scheme.runtime.value.SBigInteger;
import scheme.runtime.value.SRational;

/**
 * The generic (slow) path of arithmetic (design.md D1): exact integers ({@code long},
 * {@link SBigInteger}), exact rationals ({@link SRational}) and flonums. Every result is canonical:
 * big results that fit in a {@code long} are demoted, and a rational with denominator 1 is an
 * integer (AGENTS.md "Truffle conventions"). Mixing exact and inexact gives inexact. Complex
 * numbers arrive with the rest of the numeric tower (M4).
 */
final class Arithmetic {

    private Arithmetic() {
    }

    enum Op {
        ADD,
        SUB,
        MUL,
        DIV
    }

    static boolean isExact(Object x) {
        return x instanceof Long || x instanceof SBigInteger || x instanceof SRational;
    }

    static boolean isReal(Object x) {
        return x instanceof Double || isExact(x);
    }

    @TruffleBoundary
    static Object check(String who, Object x) {
        if (isReal(x)) {
            return x;
        }
        throw new SchemeError(who + ": not a number (or not supported until M4): " + Printer.print(x, Printer.Mode.WRITE));
    }

    static BigInteger numerator(Object x) {
        return x instanceof Long l ? BigInteger.valueOf(l) : x instanceof SBigInteger b ? b.value : ((SRational) x).numerator;
    }

    static BigInteger denominator(Object x) {
        return x instanceof SRational r ? r.denominator : BigInteger.ONE;
    }

    @TruffleBoundary
    static double flo(Object x) {
        if (x instanceof Double d) {
            return d;
        }
        if (x instanceof Long l) {
            return l;
        }
        if (x instanceof SBigInteger b) {
            return b.value.doubleValue();
        }
        SRational r = (SRational) x;
        return new BigDecimal(r.numerator).divide(new BigDecimal(r.denominator), MathContext.DECIMAL64).doubleValue();
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
                case DIV -> x / y;
            };
        }
        BigInteger an = numerator(a);
        BigInteger ad = denominator(a);
        BigInteger bn = numerator(b);
        BigInteger bd = denominator(b);
        if (ad.equals(BigInteger.ONE) && bd.equals(BigInteger.ONE) && op != Op.DIV) {
            return SBigInteger.normalize(switch (op) {
                case ADD -> an.add(bn);
                case SUB -> an.subtract(bn);
                default -> an.multiply(bn);
            });
        }
        return switch (op) {
            case ADD -> SRational.make(an.multiply(bd).add(bn.multiply(ad)), ad.multiply(bd));
            case SUB -> SRational.make(an.multiply(bd).subtract(bn.multiply(ad)), ad.multiply(bd));
            case MUL -> SRational.make(an.multiply(bn), ad.multiply(bd));
            case DIV -> {
                if (bn.signum() == 0) {
                    throw new SchemeError(who + ": division by zero");
                }
                yield SRational.make(an.multiply(bd), ad.multiply(bn));
            }
        };
    }

    /** -1, 0 or 1. */
    @TruffleBoundary
    static int compare(String who, Object a, Object b) {
        check(who, a);
        check(who, b);
        if (a instanceof Double || b instanceof Double) {
            return Double.compare(flo(a), flo(b));
        }
        return numerator(a).multiply(denominator(b)).compareTo(numerator(b).multiply(denominator(a)));
    }
}
