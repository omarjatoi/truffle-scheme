package scheme.builtins;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.runtime.SchemeError;
import scheme.runtime.printer.Printer;
import scheme.runtime.value.Numbers;
import scheme.runtime.value.SBigInteger;
import scheme.runtime.value.SComplex;
import scheme.runtime.value.SRational;

/**
 * The generic (slow) path of the numeric tower (design.md D1), behind the {@code long} and
 * {@code double} fast paths of ArithmeticNodes: exact integers ({@code long}, {@link SBigInteger}),
 * exact rationals ({@link SRational}), flonums, and complex numbers ({@link SComplex}, both parts
 * of the same exactness). Every result is canonical: big results that fit in a {@code long} are
 * demoted, a rational with denominator 1 is an integer, and an exact zero imaginary part makes a
 * number real. Mixing exact and inexact gives inexact.
 *
 * <p>Comparisons follow IEEE 754 for flonums (NaN is unordered, {@code -0.0 = 0.0}), and compare an
 * exact number with a flonum exactly, so {@code =} stays transitive (R7RS §6.2.6).
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

    /** {@link #compare}'s result when either argument is NaN. */
    static final int UNORDERED = 2;

    static boolean isExact(Object x) {
        return isExactReal(x) || x instanceof SComplex c && isExactReal(c.real);
    }

    static boolean isExactReal(Object x) {
        return x instanceof Long || x instanceof SBigInteger || x instanceof SRational;
    }

    static boolean isExactInteger(Object x) {
        return x instanceof Long || x instanceof SBigInteger;
    }

    static boolean isReal(Object x) {
        return x instanceof Double || x instanceof Long || x instanceof SBigInteger || x instanceof SRational;
    }

    static boolean isNumber(Object x) {
        return isReal(x) || x instanceof SComplex;
    }

    static boolean isInteger(Object x) {
        return isExactInteger(x) || x instanceof Double d && Double.isFinite(d) && d == Math.rint(d);
    }

    @TruffleBoundary
    static Object check(String who, Object x) {
        if (isNumber(x)) {
            return x;
        }
        throw notA(who, "number", x);
    }

    @TruffleBoundary
    static Object checkReal(String who, Object x) {
        if (isReal(x)) {
            return x;
        }
        throw notA(who, "real number", x);
    }

    @TruffleBoundary
    static Object checkInteger(String who, Object x) {
        if (isInteger(x)) {
            return x;
        }
        throw notA(who, "integer", x);
    }

    @TruffleBoundary
    static SchemeError notA(String who, String what, Object x) {
        return new SchemeError(who + ": not a " + what + ": " + Printer.print(x, Printer.Mode.WRITE));
    }

    static BigInteger numerator(Object x) {
        return x instanceof Long l ? BigInteger.valueOf(l) : x instanceof SBigInteger b ? b.value : ((SRational) x).numerator;
    }

    static BigInteger denominator(Object x) {
        return x instanceof SRational r ? r.denominator : BigInteger.ONE;
    }

    static Object realPart(Object x) {
        return x instanceof SComplex c ? c.real : x;
    }

    static Object imagPart(Object x) {
        return x instanceof SComplex c ? c.imag : 0L;
    }

    /** The flonum nearest to a real number. */
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
        return Numbers.nearestDouble(r.numerator, r.denominator);
    }

    /** The exactly equal exact number (design.md D1): a flonum becomes the rational it denotes. */
    @TruffleBoundary
    static Object exact(String who, Object x) {
        if (x instanceof SComplex c) {
            return SComplex.make(exact(who, c.real), exact(who, c.imag));
        }
        if (!(x instanceof Double d)) {
            return check(who, x);
        }
        if (!Double.isFinite(d)) {
            throw notA(who, "finite number", x);
        }
        BigDecimal b = new BigDecimal(d);
        return b.scale() <= 0 ? SBigInteger.normalize(b.toBigIntegerExact()) : SRational.make(b.unscaledValue(), BigInteger.TEN.pow(b.scale()));
    }

    /** The inexact number nearest to {@code x}. */
    @TruffleBoundary
    static Object inexact(Object x) {
        if (x instanceof SComplex c) {
            return SComplex.make(flo(c.real), flo(c.imag));
        }
        return flo(x);
    }

    // ------------------------------------------------------------------------------------------
    // + - * /

    @TruffleBoundary
    static Object apply(String who, Op op, Object a, Object b) {
        check(who, a);
        check(who, b);
        if (op == Op.DIV && b instanceof Long d && d == 0) {
            // R7RS §6.2.6: "It is an error if any argument of / other than the first is an exact
            // zero", whatever the others are: an inexact dividend included (design.md D9). An exact
            // zero is always the fixnum 0. The typed Div operation's flonum path never gets here.
            throw new SchemeError(who + ": division by zero");
        }
        if (!(a instanceof SComplex) && !(b instanceof SComplex)) {
            return real(who, op, a, b);
        }
        Object ar = realPart(a);
        Object ai = imagPart(a);
        Object br = realPart(b);
        Object bi = imagPart(b);
        return switch (op) {
            case ADD -> SComplex.make(real(who, Op.ADD, ar, br), real(who, Op.ADD, ai, bi));
            case SUB -> SComplex.make(real(who, Op.SUB, ar, br), real(who, Op.SUB, ai, bi));
            case MUL -> SComplex.make(real(who, Op.SUB, real(who, Op.MUL, ar, br), real(who, Op.MUL, ai, bi)),
                            real(who, Op.ADD, real(who, Op.MUL, ar, bi), real(who, Op.MUL, ai, br)));
            case DIV -> {
                Object d = real(who, Op.ADD, real(who, Op.MUL, br, br), real(who, Op.MUL, bi, bi));
                Object re = real(who, Op.ADD, real(who, Op.MUL, ar, br), real(who, Op.MUL, ai, bi));
                Object im = real(who, Op.SUB, real(who, Op.MUL, ai, br), real(who, Op.MUL, ar, bi));
                yield SComplex.make(real(who, Op.DIV, re, d), real(who, Op.DIV, im, d));
            }
        };
    }

    private static Object real(String who, Op op, Object a, Object b) {
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

    static Object add(Object a, Object b) {
        return apply("+", Op.ADD, a, b);
    }

    static Object sub(Object a, Object b) {
        return apply("-", Op.SUB, a, b);
    }

    static Object mul(Object a, Object b) {
        return apply("*", Op.MUL, a, b);
    }

    static Object div(Object a, Object b) {
        return apply("/", Op.DIV, a, b);
    }

    // ------------------------------------------------------------------------------------------
    // Comparison

    /** -1, 0, 1, or {@link #UNORDERED} if either is NaN. Real numbers only. */
    @TruffleBoundary
    static int compare(String who, Object a, Object b) {
        checkReal(who, a);
        checkReal(who, b);
        if (a instanceof Double x && b instanceof Double y) {
            return compareDoubles(x, y);
        }
        if (a instanceof Double x) {
            return compareWithExact(x, b);
        }
        if (b instanceof Double y) {
            int c = compareWithExact(y, a);
            return c == UNORDERED ? c : -c;
        }
        return numerator(a).multiply(denominator(b)).compareTo(numerator(b).multiply(denominator(a)));
    }

    static int compareDoubles(double x, double y) {
        return x < y ? -1 : x > y ? 1 : x == y ? 0 : UNORDERED;
    }

    /** A flonum against an exact number, exactly. */
    private static int compareWithExact(double x, Object exact) {
        if (Double.isNaN(x)) {
            return UNORDERED;
        }
        if (Double.isInfinite(x)) {
            return x > 0 ? 1 : -1;
        }
        Object e = exact("compare", x);
        return numerator(e).multiply(denominator(exact)).compareTo(numerator(exact).multiply(denominator(e)));
    }

    /** {@code =} on any numbers: complex numbers are equal when both parts are. */
    @TruffleBoundary
    static boolean numericEquals(String who, Object a, Object b) {
        check(who, a);
        check(who, b);
        if (a instanceof SComplex || b instanceof SComplex) {
            return compare(who, realPart(a), realPart(b)) == 0 && compare(who, imagPart(a), imagPart(b)) == 0;
        }
        return compare(who, a, b) == 0;
    }

    static int sign(String who, Object x) {
        int c = compare(who, x, 0L);
        return c == UNORDERED ? 0 : c;
    }

    // ------------------------------------------------------------------------------------------
    // Integer division (R7RS §6.2.6): floor and truncate families, on integers (flonums too)

    /** {quotient, remainder} of {@code n / d}, floored or truncated. */
    @TruffleBoundary
    static Object[] divide(String who, Object n, Object d, boolean floor) {
        checkInteger(who, n);
        checkInteger(who, d);
        if (n instanceof Double || d instanceof Double) {
            double x = flo(n);
            double y = flo(d);
            if (y == 0) {
                throw new SchemeError(who + ": division by zero");
            }
            double q = floor ? Math.floor(x / y) : (x / y < 0 ? Math.ceil(x / y) : Math.floor(x / y));
            return new Object[]{q, x - q * y};
        }
        BigInteger x = numerator(n);
        BigInteger y = numerator(d);
        if (y.signum() == 0) {
            throw new SchemeError(who + ": division by zero");
        }
        BigInteger[] qr = x.divideAndRemainder(y);
        if (floor && qr[1].signum() != 0 && qr[1].signum() != y.signum()) {
            qr[0] = qr[0].subtract(BigInteger.ONE);
            qr[1] = qr[1].add(y);
        }
        return new Object[]{SBigInteger.normalize(qr[0]), SBigInteger.normalize(qr[1])};
    }

    @TruffleBoundary
    static Object gcd(Object a, Object b) {
        checkInteger("gcd", a);
        checkInteger("gcd", b);
        if (a instanceof Double || b instanceof Double) {
            return flo(gcd(exact("gcd", a), exact("gcd", b)));
        }
        return SBigInteger.normalize(numerator(a).gcd(numerator(b)));
    }

    @TruffleBoundary
    static Object lcm(Object a, Object b) {
        checkInteger("lcm", a);
        checkInteger("lcm", b);
        if (a instanceof Double || b instanceof Double) {
            return flo(lcm(exact("lcm", a), exact("lcm", b)));
        }
        BigInteger x = numerator(a).abs();
        BigInteger y = numerator(b).abs();
        if (x.signum() == 0 || y.signum() == 0) {
            return 0L;
        }
        return SBigInteger.normalize(x.divide(x.gcd(y)).multiply(y));
    }

    // ------------------------------------------------------------------------------------------
    // Rounding

    enum Rounding {
        FLOOR,
        CEILING,
        TRUNCATE,
        ROUND
    }

    /** floor, ceiling, truncate and round (to even): exact for exact arguments. */
    @TruffleBoundary
    static Object round(String who, Rounding mode, Object x) {
        checkReal(who, x);
        if (x instanceof Double d) {
            return switch (mode) {
                case FLOOR -> Math.floor(d);
                case CEILING -> Math.ceil(d);
                case TRUNCATE -> d < 0 ? Math.ceil(d) : Math.floor(d);
                case ROUND -> Math.rint(d);
            };
        }
        if (!(x instanceof SRational r)) {
            return x;
        }
        RoundingMode rounding = switch (mode) {
            case FLOOR -> RoundingMode.FLOOR;
            case CEILING -> RoundingMode.CEILING;
            case TRUNCATE -> RoundingMode.DOWN;
            case ROUND -> RoundingMode.HALF_EVEN;
        };
        return SBigInteger.normalize(new BigDecimal(r.numerator).divide(new BigDecimal(r.denominator), 0, rounding).toBigIntegerExact());
    }

    /** {@code (- z)}: the negation, so that the sign of a zero flonum (part) flips. */
    @TruffleBoundary
    static Object negate(Object z) {
        check("-", z);
        if (z instanceof Double d) {
            return -d;
        } else if (z instanceof SComplex c) {
            return SComplex.make(negate(c.real), negate(c.imag));
        }
        return sub(0L, z);
    }

    /** The simplest rational within {@code y} of {@code x} (R7RS §6.2.6), by the Stern–Brocot walk. */
    @TruffleBoundary
    static Object rationalize(Object x, Object y) {
        checkReal("rationalize", x);
        checkReal("rationalize", y);
        boolean inexact = x instanceof Double || y instanceof Double;
        if (inexact && (!Double.isFinite(flo(x)) || !Double.isFinite(flo(y)))) {
            // Every real is within an infinite y of a finite x, and 0 is the simplest; no rational is
            // within any y of an infinite x, or (IEEE: inf - inf) within infinite y of it.
            if (Double.isNaN(flo(x)) || Double.isNaN(flo(y)) || Double.isInfinite(flo(x)) && Double.isInfinite(flo(y))) {
                return Double.NaN;
            }
            return Double.isInfinite(flo(y)) ? 0.0 : flo(x);
        }
        Object ex = exact("rationalize", x);
        Object ey = exact("rationalize", y);
        if (sign("rationalize", ey) < 0) {
            ey = sub(0L, ey);
        }
        Object lo = sub(ex, ey);
        Object hi = add(ex, ey);
        Object result;
        if (sign("rationalize", lo) > 0) {
            result = simplest(lo, hi);
        } else if (sign("rationalize", hi) < 0) {
            result = sub(0L, simplest(sub(0L, hi), sub(0L, lo)));
        } else {
            result = 0L;
        }
        return inexact ? flo(result) : result;
    }

    /** The simplest rational in [lo, hi], 0 < lo <= hi. */
    private static Object simplest(Object lo, Object hi) {
        Object fl = round("rationalize", Rounding.FLOOR, lo);
        if (compare("rationalize", fl, lo) == 0) {
            return fl;
        }
        if (compare("rationalize", fl, round("rationalize", Rounding.FLOOR, hi)) < 0) {
            return add(fl, 1L);
        }
        Object rest = simplest(div(1L, sub(hi, fl)), div(1L, sub(lo, fl)));
        return add(fl, div(1L, rest));
    }

    // ------------------------------------------------------------------------------------------
    // Transcendental functions (R7RS §6.2.6, with its branch cuts)
    //
    // StrictMath (fdlibm), never Math, whose results differ by an ulp between platforms and between
    // the JVM and native launchers: a program writes the same digits everywhere (design.md D1,
    // "Transcendental functions are fdlibm's"). Math.sqrt is IEEE 754's, the same everywhere.

    /** A flonum or flonum-part complex result, real when the imaginary part is exactly 0.0 from a real argument. */
    private static Object complexResult(double re, double im) {
        return SComplex.make(re, im);
    }

    @TruffleBoundary
    static Object exp(Object z) {
        check("exp", z);
        if (!(z instanceof SComplex)) {
            return StrictMath.exp(flo(z));
        }
        double x = flo(realPart(z));
        double y = flo(imagPart(z));
        double m = StrictMath.exp(x);
        return complexResult(m * StrictMath.cos(y), m * StrictMath.sin(y));
    }

    @TruffleBoundary
    static Object log(Object z) {
        check("log", z);
        // -0.0 is on the negative side of the branch cut: (log -0.0) is -inf.0+pi*i.
        if (!(z instanceof SComplex) && sign("log", z) >= 0 && !(z instanceof Double d && d == 0.0 && Double.doubleToRawLongBits(d) != 0)) {
            return StrictMath.log(flo(z));
        }
        return complexResult(StrictMath.log(magnitudeDouble(z)), angleDouble(z));
    }

    static double magnitudeDouble(Object z) {
        return StrictMath.hypot(flo(realPart(z)), flo(imagPart(z)));
    }

    static double angleDouble(Object z) {
        return StrictMath.atan2(flo(imagPart(z)), flo(realPart(z)));
    }

    @TruffleBoundary
    static Object magnitude(Object z) {
        check("magnitude", z);
        if (!(z instanceof SComplex c)) {
            return sign("magnitude", z) < 0 ? sub(0L, z) : z;
        }
        Object squares = add(mul(c.real, c.real), mul(c.imag, c.imag));
        return sqrt(squares);
    }

    @TruffleBoundary
    static Object angle(Object z) {
        check("angle", z);
        if (!(z instanceof SComplex)) {
            if (z instanceof Double d) {
                return StrictMath.atan2(0.0, d);
            }
            return sign("angle", z) < 0 ? Math.PI : (Object) 0L;
        }
        return angleDouble(z);
    }

    /** sqrt: exact for exact squares (and their negatives, as exact imaginary numbers). */
    @TruffleBoundary
    static Object sqrt(Object z) {
        check("sqrt", z);
        if (isExact(z) && !(z instanceof SComplex)) {
            boolean negative = sign("sqrt", z) < 0;
            Object a = negative ? sub(0L, z) : z;
            BigInteger[] n = numerator(a).sqrtAndRemainder();
            BigInteger[] d = denominator(a).sqrtAndRemainder();
            if (n[1].signum() == 0 && d[1].signum() == 0) {
                Object root = SRational.make(n[0], d[0]);
                return negative ? SComplex.make(0L, root) : root;
            }
        }
        if (!(z instanceof SComplex)) {
            double x = flo(z);
            return x >= 0 || Double.isNaN(x) ? (Object) Math.sqrt(x) : complexResult(0.0, Math.sqrt(-x));
        }
        // The principal root: Re >= 0, and the cut along the negative real axis.
        double x = flo(realPart(z));
        double y = flo(imagPart(z));
        double r = StrictMath.hypot(x, y);
        double re = Math.sqrt((r + x) / 2);
        double im = Math.sqrt((r - x) / 2);
        return complexResult(re, y < 0 ? -im : im);
    }

    @TruffleBoundary
    static Object trig(String who, Object z) {
        check(who, z);
        if (!(z instanceof SComplex)) {
            double x = flo(z);
            switch (who) {
                case "sin":
                    return StrictMath.sin(x);
                case "cos":
                    return StrictMath.cos(x);
                case "tan":
                    return StrictMath.tan(x);
                case "asin":
                    if (x >= -1 && x <= 1) {
                        return StrictMath.asin(x);
                    }
                    break;
                case "acos":
                    if (x >= -1 && x <= 1) {
                        return StrictMath.acos(x);
                    }
                    break;
                default:
                    return StrictMath.atan(x);
            }
        }
        double x = flo(realPart(z));
        double y = flo(imagPart(z));
        switch (who) {
            case "sin":
                return complexResult(StrictMath.sin(x) * StrictMath.cosh(y), StrictMath.cos(x) * StrictMath.sinh(y));
            case "cos":
                return complexResult(StrictMath.cos(x) * StrictMath.cosh(y), -StrictMath.sin(x) * StrictMath.sinh(y));
            case "tan":
                return div(trig("sin", z), trig("cos", z));
            case "asin": {
                // -i log(iz + sqrt(1 - z^2))
                Object iz = mul(SComplex.make(0.0, 1.0), inexact(z));
                Object w = log(add(iz, sqrt(sub(1.0, mul(z, z)))));
                return mul(SComplex.make(0.0, -1.0), w);
            }
            case "acos":
                // pi/2 - asin z
                return sub(Math.PI / 2, trig("asin", z));
            default: {
                // (log(1 + iz) - log(1 - iz)) / 2i
                Object iz = mul(SComplex.make(0.0, 1.0), inexact(z));
                return div(sub(log(add(1.0, iz)), log(sub(1.0, iz))), SComplex.make(0.0, 2.0));
            }
        }
    }

    /** {@code (expt z1 z2)}: exact for an exact base and exact integer power. */
    @TruffleBoundary
    static Object expt(Object base, Object power) {
        check("expt", base);
        check("expt", power);
        if (isExactInteger(power) && (isExact(base) || base instanceof Double)) {
            BigInteger p = numerator(power);
            if (base instanceof Double d) {
                return StrictMath.pow(d, p.doubleValue());
            }
            if (p.bitLength() > 31) {
                if (isExactInteger(base) && numerator(base).abs().compareTo(BigInteger.ONE) <= 0) {
                    return numerator(base).signum() == 0 ? (p.signum() > 0 ? 0L : base) : p.testBit(0) ? base : (Object) 1L;
                }
                throw new SchemeError("expt: exponent too large: " + p);
            }
            int e = p.intValue();
            Object result = 1L;
            Object square = base;
            for (int k = Math.abs(e); k > 0; k >>= 1) {
                if ((k & 1) != 0) {
                    result = mul(result, square);
                }
                if (k > 1) {
                    square = mul(square, square);
                }
            }
            return e >= 0 ? result : div(1L, result);
        }
        if (isReal(base) && isReal(power) && sign("expt", base) >= 0) {
            if (sign("expt", base) == 0) {
                return sign("expt", power) == 0 ? (isExact(base) && isExact(power) ? (Object) 1L : (Object) 1.0) : (isExact(base) && isExact(power) ? (Object) 0L : (Object) 0.0);
            }
            return StrictMath.pow(flo(base), flo(power));
        }
        if (sign("expt", realPart(base)) == 0 && sign("expt", imagPart(base)) == 0) {
            return isExact(base) && isExact(power) ? (Object) 0L : (Object) 0.0;
        }
        return exp(mul(power, log(base)));
    }

    @TruffleBoundary
    static Object makePolar(Object magnitude, Object angle) {
        checkReal("make-polar", magnitude);
        checkReal("make-polar", angle);
        if (isExact(angle) && sign("make-polar", angle) == 0) {
            return magnitude;
        }
        double m = flo(magnitude);
        double a = flo(angle);
        return complexResult(m * StrictMath.cos(a), m * StrictMath.sin(a));
    }
}
