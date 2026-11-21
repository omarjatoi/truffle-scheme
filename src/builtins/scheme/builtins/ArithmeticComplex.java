package scheme.builtins;

import scheme.runtime.value.SComplex;

/**
 * The elementary functions of a complex flonum x+iy (R7RS §6.2.6), each a pair {re, im}: C99 Annex
 * G.6's special values at infinities and NaNs, which R7RS leaves open, with Kahan's formulas
 * ("Branch Cuts for Complex Elementary Functions, or Much Ado About Nothing's Sign Bit", 1987) for
 * the finite ones, which neither cancel near the cuts nor lose the signs of zeros (M13-02, design.md
 * D1). These are C99's functions, signed zeros and all; {@link Arithmetic} applies R7RS's text where
 * it differs, on sqrt's, asin's and acos's cuts. Every function is StrictMath's (fdlibm's), so the
 * bits are the same on every platform (design.md D1, "Transcendental functions are fdlibm's").
 */
final class ArithmeticComplex {

    private ArithmeticComplex() {
    }

    private static final double INF = Double.POSITIVE_INFINITY;
    private static final double NAN = Double.NaN;
    private static final double LN2 = 0x1.62e42fefa39efp-1;

    /**
     * Beyond 2^28 in either part, asin's and acos's arcsinh is log 2w (to 2^-58 relative), whose
     * real part is computed without overflow; within, Kahan's products of roots, which overflow
     * near the flonums' range.
     */
    private static final double LARGE = 0x1p28;

    /** Kahan's θ for atanh: beyond it, (1 - x)² and y² could overflow, and atanh z is 1/z + iπ/2. */
    private static final double THETA = 0x1p500;

    /** Whether a flonum's sign is -: -0.0 included, a NaN not. */
    static boolean signBit(double x) {
        return x < 0 || x == 0 && 1 / x < 0;
    }

    /**
     * C99's csqrt (G.6.4.2): the principal root, csqrt(conj z) = conj csqrt(z) with the signs of
     * zeros, and the special values: x+i∞ is +∞+i∞ for every x, a NaN too; -∞+iy is +0+i∞ and
     * +∞+iy is +∞+i0 for a finite y ≥ +0; -∞+iNaN is NaN+i∞ (G.6 leaves the sign open), +∞+iNaN is
     * +∞+iNaN; any other NaN gives NaN+iNaN. Finite: Kahan's (and CACM Algorithm 312's) t =
     * √((|x| + |z|)/2), and the other part y/2t, where (|z| - x)/2 would cancel; parts beyond 2^1022
     * are scaled by 1/4 first and parts below 2^-1021 by 2^54, exactly, so the sum neither
     * overflows nor loses bits as a subnormal.
     */
    static double[] sqrt(double x, double y) {
        if (Double.isInfinite(y)) {
            return new double[]{INF, y};
        } else if (Double.isNaN(x)) {
            return new double[]{NAN, NAN};
        } else if (x == INF) {
            return new double[]{INF, Double.isNaN(y) ? NAN : StrictMath.copySign(0.0, y)};
        } else if (x == -INF) {
            return new double[]{Double.isNaN(y) ? NAN : 0.0, StrictMath.copySign(INF, y)};
        } else if (Double.isNaN(y)) {
            return new double[]{NAN, NAN};
        } else if (x == 0 && y == 0) {
            return new double[]{0.0, y};
        }
        double ax = Math.abs(x);
        double ay = Math.abs(y);
        int scale = 0;
        if (ax >= 0x1p1022 || ay >= 0x1p1022) {
            x *= 0.25;
            y *= 0.25;
            scale = 1;
        } else if (ax < 0x1p-1021 && ay < 0x1p-1021) {
            x = Math.scalb(x, 54);
            y = Math.scalb(y, 54);
            scale = -27;
        }
        double t = Math.sqrt((Math.abs(x) + StrictMath.hypot(x, y)) * 0.5);
        double re;
        double im;
        if (x >= 0) {
            re = t;
            im = y / (2 * t);
        } else {
            re = Math.abs(y) / (2 * t);
            im = StrictMath.copySign(t, y);
        }
        return new double[]{Math.scalb(re, scale), Math.scalb(im, scale)};
    }

    /**
     * C99's cexp (G.6.3.1): e^x cis y, and the special values: x±i0 is e^x±i0, also for an infinite
     * or NaN x (e^x·sin 0 would make +∞·0 a NaN); -∞+iy is +0 cis y and +∞+iy is +∞ cis y for a
     * finite y; an infinite or NaN y gives NaN+iNaN, but ±0±i0 for x = -∞ and ±∞+iNaN for x = +∞
     * (G.6 leaves those signs open; ours are +).
     */
    static Object exp(double x, double y) {
        if (y == 0) {
            return SComplex.make(StrictMath.exp(x), y);
        } else if (!Double.isFinite(y)) {
            return x == -INF ? SComplex.make(0.0, 0.0) : x == INF ? SComplex.make(INF, NAN) : SComplex.make(NAN, NAN);
        }
        double m = StrictMath.exp(x);
        return SComplex.make(m * StrictMath.cos(y), m * StrictMath.sin(y));
    }

    /** An odd function with conjugate symmetry, of a pair. */
    @FunctionalInterface
    interface Quadrant {
        double[] apply(double x, double y);
    }

    /**
     * f(x+iy) for an odd f with C99 Annex G.6's conjugate symmetry, f(conj z) = conj f(z) (asin and
     * atan: casin(z) = -i casinh(iz) and catan(z) = -i catanh(iz), and casinh and catanh are odd and
     * symmetric): {@code f} on the quadrant whose real part's sign is + and imaginary part's -, and
     * elsewhere by the symmetries, so both hold bit for bit, the signs of zeros included (M12-01). A
     * NaN counts as +.
     */
    static double[] bySymmetry(double x, double y, Quadrant f) {
        boolean negated = signBit(x);
        boolean conjugated = !Double.isNaN(y) && signBit(y) == negated;
        double u = negated ? -x : x;
        double v = negated ? -y : y;
        double[] w = f.apply(u, conjugated ? -v : v);
        double re = w[0];
        double im = conjugated ? -w[1] : w[1];
        return negated ? new double[]{-re, -im} : new double[]{re, im};
    }

    /**
     * C99's casin, -i casinh(iz), for x ≥ +0 and y ≤ -0 ({@link #bySymmetry}): Kahan's arctan(x /
     * Re(√(1-z)·√(1+z))) + i arcsinh(Im(conj(√(1-z))·√(1+z))), and beyond {@link #LARGE},
     * casinh(iz) as log 2iz; G.6.2.2's special values of casinh at infinities and NaNs.
     */
    static double[] asin(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            double[] w = asinhSpecial(-y, x);
            return new double[]{w[1], -w[0]};
        } else if (x >= LARGE || -y >= LARGE) {
            return new double[]{StrictMath.atan2(x, -y), -logTwice(x, y)};
        }
        double[] s1 = sqrt(1 - x, -y);
        double[] s2 = sqrt(1 + x, y);
        return new double[]{StrictMath.atan2(x, s1[0] * s2[0] - s1[1] * s2[1]), asinh(s1[0] * s2[1] - s1[1] * s2[0])};
    }

    /**
     * C99's cacos (G.6.1.1), for any x+iy: Kahan's 2 arctan(Re √(1-z) / Re √(1+z)) + i
     * arcsinh(Im(conj(√(1+z))·√(1-z))), not π/2 - asin z, which cancels; beyond {@link #LARGE},
     * arg z - i log 2z on the upper half plane; and G.6.1.1's special values.
     */
    static double[] acos(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            double re;
            double im;
            double ay = Math.abs(y);
            if (ay == INF) {
                re = Double.isNaN(x) ? NAN : x == INF ? Math.PI / 4 : x == -INF ? 3 * Math.PI / 4 : Math.PI / 2;
                im = -INF;
            } else if (Double.isInfinite(x)) {
                re = Double.isNaN(y) ? NAN : x > 0 ? 0.0 : Math.PI;
                im = Double.isNaN(y) ? INF : -INF;
            } else if (Double.isNaN(y) && x == 0) {
                re = Math.PI / 2;
                im = NAN;
            } else {
                re = NAN;
                im = NAN;
            }
            return new double[]{re, signBit(y) ? -im : im};
        } else if (Math.abs(x) >= LARGE || Math.abs(y) >= LARGE) {
            return new double[]{StrictMath.atan2(Math.abs(y), x), -StrictMath.copySign(logTwice(x, y), y)};
        }
        double[] s1 = sqrt(1 - x, -y);
        double[] s2 = sqrt(1 + x, y);
        return new double[]{2 * StrictMath.atan2(s1[0], s2[0]), asinh(s2[0] * s1[1] - s2[1] * s1[0])};
    }

    /**
     * C99's catan, -i catanh(iz), for x ≥ +0 and y ≤ -0 ({@link #bySymmetry}), so catanh's argument
     * a+ib = -y+ix has a, b ≥ +0: Kahan's ¼ log1p(4a / ((1-a)² + b²)) + i ½ arg((1-a)(1+a) - b² +
     * 2ib), not a difference of logs that cancels; at a = 1, ¼ log(4 + b²) - ½ log b + i (π/2 +
     * arctan(b/2))/2, where b² could underflow; beyond {@link #THETA}, Re(1/z) + iπ/2; and G.6.2.3's
     * special values of catanh (catanh(1+i0) is +∞+i0).
     */
    static double[] atan(double x, double y) {
        double a = -y;
        double b = x;
        double re;
        double im;
        if (!Double.isFinite(a) || !Double.isFinite(b)) {
            if (a == INF || b == INF) {
                re = 0.0;
                im = Double.isNaN(b) ? NAN : Math.PI / 2;
            } else if (a == 0) {
                re = a;
                im = NAN;
            } else {
                re = NAN;
                im = NAN;
            }
        } else if (a == 1 && b == 0) {
            re = INF;
            im = b;
        } else if (a > THETA || b > THETA) {
            int k = Math.getExponent(Math.max(a, b));
            double u = Math.scalb(a, -k);
            double v = Math.scalb(b, -k);
            re = Math.scalb(u / (u * u + v * v), -k);
            im = Math.PI / 2;
        } else if (a == 1) {
            re = StrictMath.log(Math.sqrt(Math.sqrt(4 + b * b)) / Math.sqrt(b));
            im = (Math.PI / 2 + StrictMath.atan(b / 2)) / 2;
        } else {
            double t = 1 - a;
            re = StrictMath.log1p(4 * a / (t * t + b * b)) / 4;
            im = StrictMath.atan2(2 * b, t * (1 + a) - b * b) / 2;
        }
        return new double[]{im, -re};
    }

    /**
     * C99's casinh(a+ib) (G.6.2.2) where a or b is an infinity or a NaN: a+i∞ is +∞+iπ/2 for a
     * finite a, +∞+iπ/4 for a = +∞, ±∞+iNaN for a NaN; +∞+ib is +∞+i0 for a finite b; +∞+iNaN is
     * +∞+iNaN; NaN+i0 is NaN+i0; any other NaN gives NaN+iNaN. casinh is odd and symmetric, so the
     * signs are a's and b's.
     */
    private static double[] asinhSpecial(double a, double b) {
        double re;
        double im;
        if (Math.abs(b) == INF) {
            re = INF;
            im = Double.isNaN(a) ? NAN : Math.abs(a) == INF ? Math.PI / 4 : Math.PI / 2;
        } else if (Math.abs(a) == INF) {
            re = INF;
            im = Double.isNaN(b) ? NAN : 0.0;
        } else if (Double.isNaN(a) && b == 0) {
            re = NAN;
            im = 0.0;
        } else {
            re = NAN;
            im = NAN;
        }
        return new double[]{StrictMath.copySign(re, a), StrictMath.copySign(im, b)};
    }

    /** log 2|x+iy|, for |x+iy| ≥ 2^28, without the overflow of 2|z| or |z|. */
    private static double logTwice(double x, double y) {
        return StrictMath.log(StrictMath.hypot(x * 0.5, y * 0.5)) + 2 * LN2;
    }

    /**
     * fdlibm's asinh (s_asinh.c), which StrictMath doesn't have, on StrictMath's log and log1p: odd,
     * bit for bit, so the signs of zeros keep.
     */
    static double asinh(double x) {
        double t = Math.abs(x);
        double w;
        if (!Double.isFinite(x) || t < 0x1p-28) {
            return x;
        } else if (t > 0x1p28) {
            w = StrictMath.log(t) + LN2;
        } else if (t > 2.0) {
            w = StrictMath.log(2.0 * t + 1.0 / (Math.sqrt(x * x + 1.0) + t));
        } else {
            double s = x * x;
            w = StrictMath.log1p(t + s / (1.0 + Math.sqrt(1.0 + s)));
        }
        return x > 0 ? w : -w;
    }
}
