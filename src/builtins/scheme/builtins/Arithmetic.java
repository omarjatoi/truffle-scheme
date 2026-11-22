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
        return exactOfFinite(d);
    }

    /**
     * The exact number a finite flonum denotes, from its binary parts: an integer below 2^63 is a
     * fixnum at once, and anything else is {@code m * 2^e} with {@code m} odd, so a non-integer's
     * denominator is a power of two already in lowest terms. No decimal expansion and no gcd: the
     * {@code BigInteger} arithmetic that SubstrateVM, unlike HotSpot, doesn't intrinsify (design.md
     * D17, "The native launcher's steady state").
     */
    static Object exactOfFinite(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 0x1p63) {
            return (long) d;
        }
        long bits = Double.doubleToRawLongBits(d);
        int biased = (int) ((bits >>> 52) & 0x7ff);
        long mantissa = bits & 0xfffffffffffffL;
        int exponent;
        if (biased == 0) {
            exponent = -1074;
        } else {
            mantissa |= 1L << 52;
            exponent = biased - 1075;
        }
        int zeros = Long.numberOfTrailingZeros(mantissa);
        mantissa >>= zeros;
        exponent += zeros;
        BigInteger m = BigInteger.valueOf(d < 0 ? -mantissa : mantissa);
        if (exponent >= 0) {
            return SBigInteger.normalize(m.shiftLeft(exponent));
        }
        return SRational.make(m, BigInteger.ONE.shiftLeft(-exponent));
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
        if (!(a instanceof SComplex) || !(b instanceof SComplex)) {
            return realAndComplex(who, op, a, b);
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
                if (!isExact(a) || !isExact(b)) {
                    yield quotient(flo(ar), flo(ai), false, flo(br), flo(bi));
                }
                Object d = real(who, Op.ADD, real(who, Op.MUL, br, br), real(who, Op.MUL, bi, bi));
                Object re = real(who, Op.ADD, real(who, Op.MUL, ar, br), real(who, Op.MUL, ai, bi));
                Object im = real(who, Op.SUB, real(who, Op.MUL, ai, br), real(who, Op.MUL, ar, bi));
                yield SComplex.make(real(who, Op.DIV, re, d), real(who, Op.DIV, im, d));
            }
        };
    }

    /**
     * A real and a complex number, either way round: C99 Annex G.5.1's table, the real number
     * taken with each part on its own, x(u+iv) = xu + i(xv), (u+iv)/x = u/x + i(v/x), x ±
     * (u+iv) = (x ± u) + i(±v), and x/(u+iv) = x(u-iv)/(u²+v²). Never the real as x+0i, whose
     * 0·∞ made {@code (* 2. (make-rectangular +inf.0 1.))} +inf.0+nan.0i, and whose 0 + -0.0 made
     * the imaginary part of {@code (+ 1. (make-rectangular 1. -0.))} 0.0 (M11-01). Each part is
     * IEEE 754's round-to-nearest result, its sign of zero included (design.md D1).
     */
    private static Object realAndComplex(String who, Op op, Object a, Object b) {
        boolean complexFirst = a instanceof SComplex;
        SComplex z = (SComplex) (complexFirst ? a : b);
        Object x = complexFirst ? b : a;
        Object u = z.real;
        Object v = z.imag;
        // + and * of two reals commute, also in IEEE 754, its signs of zero included.
        return switch (op) {
            case ADD -> SComplex.make(real(who, Op.ADD, x, u), v);
            case SUB -> complexFirst ? SComplex.make(real(who, Op.SUB, u, x), v) : SComplex.make(real(who, Op.SUB, x, u), negate(v));
            case MUL -> SComplex.make(real(who, Op.MUL, x, u), real(who, Op.MUL, x, v));
            case DIV -> {
                if (complexFirst) {
                    yield SComplex.make(real(who, Op.DIV, u, x), real(who, Op.DIV, v, x));
                } else if (!isExact(x) || !isExact(z)) {
                    yield quotient(flo(x), 0.0, true, flo(u), flo(v));
                }
                Object d = real(who, Op.ADD, real(who, Op.MUL, u, u), real(who, Op.MUL, v, v));
                yield SComplex.make(real(who, Op.DIV, real(who, Op.MUL, x, u), d), real(who, Op.DIV, real(who, Op.MUL, x, negate(v)), d));
            }
        };
    }

    /**
     * (a+ib)/(c+id) in flonums: C99 Annex G.5.1's {@code _Cdivd} (EXAMPLE 2, M12-01). The divisor's
     * parts are scaled by 2^-k, k = logb(max(|c|, |d|)), before they are squared, and the quotient's
     * parts by 2^-k after, so squares that would overflow or underflow don't, and a quotient whose
     * parts are finite comes out finite; a power of 2 scales exactly, so an ordinary quotient has
     * the same bits as the textbook ((ac+bd) + i(bc-ad))/(c²+d²). A NaN+NaN i result is then
     * recomputed by G.5.1 paragraph 4's infinity properties: a nonzero number over a zero is an
     * infinity, an infinity over a finite number an infinity, a finite number over an infinity a
     * zero (an infinity being a value with an infinite part, G.3). A real dividend ({@code real})
     * is a times the divisor's conjugate, without the terms that would multiply its 0 imaginary
     * part (M11-01), so {@code b} is unused, and a NaN in either part is recomputed, since the
     * missing terms can't spread a NaN from one part to the other.
     *
     * <p>The dividend is scaled too (M13-02), by 2^-j, and the quotient by 2^j, when its larger part
     * is outside [2^-969, 2^1021): its products with the scaled divisor, whose parts are below 2,
     * could otherwise overflow, or be subnormal and lose bits that the scaling by 2^-k shows. A
     * larger part below 2^-969 is scaled to [1, 2), exactly; one of 2^1021 or more down to 2^1020,
     * so that a part much smaller than the other loses bits only if below 2^-1019. Inside the range
     * nothing changes, so a part 2^1022 times smaller than the other keeps its bits.
     */
    private static Object quotient(double a, double b, boolean real, double c, double d) {
        double logbw = logb(fmax(Math.abs(c), Math.abs(d)));
        int k = 0;
        if (Double.isFinite(logbw)) {
            k = (int) logbw;
            c = Math.scalb(c, -k);
            d = Math.scalb(d, -k);
        }
        double logbz = logb(fmax(Math.abs(a), Math.abs(real ? 0.0 : b)));
        int j = 0;
        if (Double.isFinite(logbz) && (logbz < -969 || logbz > 1020)) {
            j = logbz < -969 ? (int) logbz : (int) logbz - 1020;
        }
        double sa = Math.scalb(a, -j);
        double sb = Math.scalb(b, -j);
        double denominator = c * c + d * d;
        double x = Math.scalb((real ? sa * c : sa * c + sb * d) / denominator, j - k);
        double y = Math.scalb((real ? sa * -d : sb * c - sa * d) / denominator, j - k);
        if (real ? Double.isNaN(x) || Double.isNaN(y) : Double.isNaN(x) && Double.isNaN(y)) {
            if (denominator == 0.0 && (!Double.isNaN(a) || !Double.isNaN(b))) {
                double infinity = StrictMath.copySign(Double.POSITIVE_INFINITY, c);
                x = infinity * a;
                y = infinity * b;
            } else if ((Double.isInfinite(a) || Double.isInfinite(b)) && Double.isFinite(c) && Double.isFinite(d)) {
                a = StrictMath.copySign(Double.isInfinite(a) ? 1.0 : 0.0, a);
                b = StrictMath.copySign(Double.isInfinite(b) ? 1.0 : 0.0, b);
                x = Double.POSITIVE_INFINITY * (real ? a * c : a * c + b * d);
                y = Double.POSITIVE_INFINITY * (real ? a * -d : b * c - a * d);
            } else if (logbw == Double.POSITIVE_INFINITY && Double.isFinite(a) && Double.isFinite(b)) {
                c = StrictMath.copySign(Double.isInfinite(c) ? 1.0 : 0.0, c);
                d = StrictMath.copySign(Double.isInfinite(d) ? 1.0 : 0.0, d);
                x = 0.0 * (real ? a * c : a * c + b * d);
                y = 0.0 * (real ? a * -d : b * c - a * d);
            }
        }
        return SComplex.make(x, y);
    }

    /** C's fmax: the larger, or the other if one is a NaN. */
    private static double fmax(double x, double y) {
        return Double.isNaN(x) ? y : Double.isNaN(y) ? x : Math.max(x, y);
    }

    /** C's logb of a non-negative x: its exponent, a subnormal's too; -inf for 0, +inf for +inf. */
    private static double logb(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x)) {
            return x;
        } else if (x == 0) {
            return Double.NEGATIVE_INFINITY;
        }
        int e = Math.getExponent(x);
        return e >= Double.MIN_EXPONENT ? e : Math.getExponent(x * 0x1p54) - 54;
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
        if (exact instanceof Long l) {
            return compareWithFixnum(x, l);
        }
        if (exact instanceof SBigInteger b && Math.abs(x) < 0x1p63) {
            // A bignum is outside the fixnums' range, so beyond any flonum below 2^63.
            return -b.value.signum();
        }
        Object e = exactOfFinite(x);
        return numerator(e).multiply(denominator(exact)).compareTo(numerator(exact).multiply(denominator(e)));
    }

    /**
     * A finite flonum against a fixnum, exactly, without converting either to a big number. A
     * fixnum within 2^53 is a flonum exactly. One beyond it is compared with the flonum truncated
     * to a {@code long}: a flonum that small truncates to a different fixnum, whose order is the
     * flonum's, and one that large is an integer, which truncates to itself.
     */
    static int compareWithFixnum(double x, long l) {
        if (Math.abs(l) <= 1L << 53) {
            return compareDoubles(x, l);
        }
        if (x >= 0x1p63) {
            return 1;
        }
        if (x < -0x1p63) {
            return -1;
        }
        return Long.compare((long) x, l);
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

    /**
     * -1, 0 or 1: the sign of an exact real number. Not of a flonum, which has cases a sign doesn't
     * tell apart: a NaN is no zero, and -0.0 is a zero that IEEE 754 and R7RS §6.2.4 treat apart
     * in transcendental functions, so each caller tests a flonum's cases itself (#242's follow-up).
     */
    static int sign(Object x) {
        if (x instanceof Long l) {
            return Long.signum(l);
        }
        return x instanceof SBigInteger b ? b.value.signum() : ((SRational) x).numerator.signum();
    }

    /** Whether a real number is a zero, exact or inexact (-0.0 too, not a NaN). */
    static boolean isZero(Object x) {
        return x instanceof Long l ? l == 0 : x instanceof Double d && d == 0;
    }

    /** Whether a flonum is -0.0. */
    static boolean isNegativeZero(Object x) {
        return x instanceof Double d && Double.doubleToRawLongBits(d) == Long.MIN_VALUE;
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
        BigInteger[] qr = ArithmeticDivision.divideAndRemainder(x, y);
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
        if (sign(ey) < 0) {
            ey = sub(0L, ey);
        }
        Object lo = sub(ex, ey);
        Object hi = add(ex, ey);
        Object result;
        if (sign(lo) > 0) {
            result = simplest(lo, hi);
        } else if (sign(hi) < 0) {
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

    private static Object complexResult(double[] z) {
        return SComplex.make(z[0], z[1]);
    }

    @TruffleBoundary
    static Object exp(Object z) {
        check("exp", z);
        if (!(z instanceof SComplex)) {
            return StrictMath.exp(flo(z));
        }
        return ArithmeticComplex.exp(flo(realPart(z)), flo(imagPart(z)));
    }

    @TruffleBoundary
    static Object log(Object z) {
        check("log", z);
        if (z instanceof Double d) {
            // -0.0 is on the negative side of the branch cut: (log -0.0) is -inf.0+pi*i (R7RS
            // §6.2.6). A NaN is NaN (§6.2.4).
            if (!(d < 0) && !isNegativeZero(d)) {
                return StrictMath.log(d);
            }
        } else if (!(z instanceof SComplex) && sign(z) >= 0) {
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
        if (z instanceof Double d) {
            // "The same as abs for a real argument" (R7RS §6.2.6): (magnitude -0.0) is 0.0.
            return Math.abs(d);
        }
        if (!(z instanceof SComplex c)) {
            return sign(z) < 0 ? sub(0L, z) : z;
        }
        if (isExact(c)) {
            // Exact when the sum of the squares is a perfect square, (magnitude 3+4i) => 5, and
            // otherwise the flonum nearest to its root, also beyond the flonums' range (M11-01).
            return sqrt(add(mul(c.real, c.real), mul(c.imag, c.imag)));
        }
        // IEEE 754's hypot: no overflow or underflow of the squares, and +inf.0 for an infinite
        // part, even with a NaN one; log's real part is the log of the same (#243's follow-up).
        return magnitudeDouble(c);
    }

    @TruffleBoundary
    static Object angle(Object z) {
        check("angle", z);
        if (!(z instanceof SComplex)) {
            if (z instanceof Double d) {
                return StrictMath.atan2(0.0, d);
            }
            return sign(z) < 0 ? Math.PI : (Object) 0L;
        }
        return angleDouble(z);
    }

    /** sqrt: exact for exact squares (and their negatives, as exact imaginary numbers). */
    @TruffleBoundary
    static Object sqrt(Object z) {
        check("sqrt", z);
        if (isExact(z) && !(z instanceof SComplex)) {
            boolean negative = sign(z) < 0;
            Object a = negative ? sub(0L, z) : z;
            BigInteger[] n = numerator(a).sqrtAndRemainder();
            BigInteger[] d = denominator(a).sqrtAndRemainder();
            if (n[1].signum() == 0 && d[1].signum() == 0) {
                Object root = SRational.make(n[0], d[0]);
                return negative ? SComplex.make(0L, root) : root;
            } else if (!(a instanceof Long l && l <= 1L << 53)) {
                // Beyond 2^53 or not an integer, flo(a) is rounded, and may be +inf.0 or 0.0.
                double root = nearestSqrt(numerator(a), denominator(a));
                return negative ? complexResult(0.0, root) : (Object) root;
            }
        }
        if (!(z instanceof SComplex)) {
            double x = flo(z);
            return x >= 0 || Double.isNaN(x) ? (Object) Math.sqrt(x) : complexResult(0.0, Math.sqrt(-x));
        }
        // C99's csqrt, Kahan's formula and G.6.4.2's special values (M13-02). On the cut, the
        // negative real axis to -inf.0, either zero gives the positive imaginary axis: R7RS §6.2.6,
        // "a zero real part and a non-negative imaginary part" (and the chibi suite's
        // (sqrt -1.0-0.0i)), where C99's csqrt takes -0.0 below the cut. Elsewhere csqrt(conj z) =
        // conj csqrt(z), the signs of zeros included, which R7RS leaves open (M12-01, design.md D1):
        // off the cut the root's real part is positive, even where it rounds to 0.0.
        double x = flo(realPart(z));
        double y = flo(imagPart(z));
        double[] root = ArithmeticComplex.sqrt(x, y);
        return complexResult(root[0], x < 0 && y == 0 ? Math.abs(root[1]) : root[1]);
    }

    /**
     * The flonum nearest to √(n/d), for n, d > 0 and n/d not an exact square: one rounding, as
     * {@code inexact} of an exact number (design.md D1), where {@code Math.sqrt(flo(n/d))} rounds
     * twice and overflows to +inf.0 (or underflows to 0.0) for an n/d beyond the flonums' range
     * whose root is inside it (M11-01). With s chosen so that n·4^s/d has about 128 bits, q =
     * ⌊√⌊n·4^s/d⌋⌋ = ⌊√(n·4^s/d)⌋ has about 64, and the root lies strictly between q and q+1 (the
     * root of a rational that isn't a square is irrational). Every boundary between two roundings
     * of a number of 64 bits to 53 (or fewer, a subnormal's) is an integer, so (q + 1/2)/2^s
     * rounds as the root does.
     */
    private static double nearestSqrt(BigInteger n, BigInteger d) {
        int s = (128 - (n.bitLength() - d.bitLength())) / 2;
        BigInteger scaled = s >= 0 ? n.shiftLeft(2 * s).divide(d) : n.divide(d.shiftLeft(-2 * s));
        BigInteger halfUlps = scaled.sqrt().shiftLeft(1).setBit(0);
        return s >= -1 ? Numbers.nearestDouble(halfUlps, BigInteger.ONE.shiftLeft(s + 1)) : Numbers.nearestDouble(halfUlps.shiftLeft(-s - 1), BigInteger.ONE);
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
                    // A NaN gives NaN (R7RS §6.2.4), not NaN+NaN i (M13-02).
                    if (x >= -1 && x <= 1 || Double.isNaN(x)) {
                        return StrictMath.asin(x);
                    }
                    break;
                case "acos":
                    if (x >= -1 && x <= 1 || Double.isNaN(x)) {
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
            case "asin":
            case "acos":
                // On the cut, the real axis beyond ±1, either zero gives the real number's value, as
                // sqrt does its cut's, through which R7RS defines asin and acos: Common Lisp's
                // "continuous with quadrant IV" beyond 1 and with quadrant II beyond -1 (M12-01).
                // On the axis between, the real number's value too, with the zero's sign (C99).
                if (y == 0 && Math.abs(x) > 1) {
                    y = x > 0 ? -0.0 : 0.0;
                } else if (y == 0 && Math.abs(x) <= 1) {
                    return who.equals("asin") ? complexResult(StrictMath.asin(x), y) : complexResult(StrictMath.acos(x), -y);
                }
                return complexResult(who.equals("asin") ? ArithmeticComplex.bySymmetry(x, y, ArithmeticComplex::asin) : ArithmeticComplex.acos(x, y));
            default:
                // On the real axis, the real number's value with the zero's sign (C99's catan); the
                // cut is the imaginary axis beyond ±i, whose zero's sign tells the side (M12-01).
                if (y == 0) {
                    return complexResult(StrictMath.atan(x), y);
                }
                return complexResult(ArithmeticComplex.bySymmetry(x, y, ArithmeticComplex::atan));
        }
    }

    /** {@code (expt z1 z2)}: exact for an exact base and exact integer power. */
    @TruffleBoundary
    static Object expt(Object base, Object power) {
        check("expt", base);
        check("expt", power);
        if (isExactInteger(power) && (isExact(base) || base instanceof Double)) {
            if (base instanceof Double d) {
                // A long's conversion and BigInteger.doubleValue both round to nearest, ties to even.
                return StrictMath.pow(d, power instanceof Long l ? (double) l : numerator(power).doubleValue());
            }
            BigInteger p = numerator(power);
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
        if (isZero(realPart(base)) && isZero(imagPart(base))) {
            return zeroToThe(isExact(base) && isExact(power), power);
        }
        if (isReal(base) && isReal(power)) {
            double b = flo(base);
            double p = flo(power);
            // IEEE 754's pow: x^0 is 1 for every x, also a NaN, an infinity or a negative x, whose
            // e^(0 log x) would be complex or NaN; a NaN power gives NaN (R7RS §6.2.4), but for
            // the base 1 (below). A positive
            // or NaN base's power is real, and so is a negative base's to an integer-valued flonum
            // power, an infinity included: every flonum beyond 2^53 is an even integer, so pow's
            // |x|^±inf is the limit. Only a negative base to a finite non-integer power is complex.
            // IEEE 754-2008 made pow(±1, ±inf) 1, and pow(+1, y) 1 for every y, a NaN too, where
            // Java's pow, C89's, is NaN; R7RS §6.2.4 allows either for a NaN (M11-01, design.md D1).
            if (p == 0 || Double.isInfinite(p) && Math.abs(b) == 1 || b == 1) {
                return 1.0;
            } else if (Double.isNaN(p) || !(b < 0) || power instanceof Double && Math.rint(p) == p) {
                return StrictMath.pow(b, p);
            }
            return negativeToThe(b, p);
        }
        return exp(mul(power, log(base)));
    }

    /**
     * {@code b^p} for a negative real b and a finite non-integer real p: R7RS §6.2.6's e^(p log b),
     * with log b = log|b| + πi. The real p multiplies each part of log b, as C99 Annex G.5.1 has a
     * real times a complex number, instead of being a complex number with an imaginary part 0 whose
     * 0·∞ would make an infinite base's power NaN: {@code (expt -inf.0 0.5)} is +inf.0+inf.0i, the
     * limit of {@code (expt -1e308 0.5)}. For a finite b, the same bits as {@code exp(mul(p, log
     * b))} were.
     */
    private static Object negativeToThe(double b, double p) {
        double m = StrictMath.exp(p * StrictMath.log(-b));
        double angle = p * Math.PI;
        return complexResult(m * StrictMath.cos(angle), m * StrictMath.sin(angle));
    }

    /**
     * {@code (expt 0 z)}, R7RS §6.2.6: "The value of 0^z is 1 if (zero? z), 0 if (real-part z) is
     * positive, and an error otherwise. Similarly for 0.0^z, with inexact results." Otherwise it is
     * 0 here too, an unspecified number (design.md D9), but a NaN in z gives NaN (§6.2.4).
     */
    private static Object zeroToThe(boolean exact, Object power) {
        Object re = realPart(power);
        Object im = imagPart(power);
        if (isZero(re) && isZero(im)) {
            return exact ? (Object) 1L : (Object) 1.0;
        } else if (re instanceof Double x && Double.isNaN(x) || im instanceof Double y && Double.isNaN(y)) {
            return Double.NaN;
        }
        return exact ? (Object) 0L : (Object) 0.0;
    }

    @TruffleBoundary
    static Object makePolar(Object magnitude, Object angle) {
        checkReal("make-polar", magnitude);
        checkReal("make-polar", angle);
        if (isExact(angle) && sign(angle) == 0) {
            return magnitude;
        }
        double m = flo(magnitude);
        double a = flo(angle);
        return complexResult(m * StrictMath.cos(a), m * StrictMath.sin(a));
    }
}
