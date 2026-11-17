package scheme.runtime.value;

import java.math.BigInteger;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Helpers on real numbers ({@code Long}, {@link SBigInteger}, {@link SRational}, {@code Double}).
 * The numeric tower proper (arithmetic, exact/inexact conversion) is M4.
 */
public final class Numbers {

    private Numbers() {
    }

    public static boolean isExact(Object real) {
        return real instanceof Long || real instanceof SBigInteger || real instanceof SRational;
    }

    public static boolean isExactZero(Object real) {
        return real instanceof Long l && l == 0L;
    }

    /** The nearest double to a real number (rationals: {@link #nearestDouble}). */
    @TruffleBoundary
    public static double toDouble(Object real) {
        if (real instanceof Double d) {
            return d;
        } else if (real instanceof Long l) {
            return l;
        } else if (real instanceof SBigInteger b) {
            return b.value.doubleValue();
        } else if (real instanceof SRational r) {
            return nearestDouble(r.numerator, r.denominator);
        }
        throw new IllegalArgumentException("not a real number: " + real);
    }

    /**
     * The flonum nearest to {@code n/d} ({@code d > 0}), ties to even: R7RS §6.2.6, inexact
     * "returns ... the inexact number that is numerically closest to the argument". One rounding
     * of the exact quotient, so never off by one at or near a tie, as rounding to a decimal first
     * would be.
     */
    @TruffleBoundary
    public static double nearestDouble(BigInteger n, BigInteger d) {
        int sign = n.signum();
        if (sign == 0) {
            return 0.0;
        }
        BigInteger a = n.abs();
        // e = floor(log2(a/d)): 2^e <= a/d < 2^(e+1).
        int e = a.bitLength() - d.bitLength();
        if ((e >= 0 ? a.compareTo(d.shiftLeft(e)) : a.shiftLeft(-e).compareTo(d)) < 0) {
            e--;
        }
        if (e > Double.MAX_EXPONENT) {
            return sign * Double.POSITIVE_INFINITY;
        }
        // The exponent of the result's last mantissa bit: 52 below the leading one, or the
        // subnormals' fixed 2^-1074.
        int ulp = Math.max(e, Double.MIN_EXPONENT) - 52;
        BigInteger num = ulp < 0 ? a.shiftLeft(-ulp) : a;
        BigInteger den = ulp < 0 ? d : d.shiftLeft(ulp);
        BigInteger[] qr = num.divideAndRemainder(den);
        long q = qr[0].longValueExact();
        int half = qr[1].shiftLeft(1).compareTo(den);
        if (half > 0 || (half == 0 && (q & 1) == 1)) {
            q++;
        }
        // q <= 2^53, so it and q * 2^ulp are exact; scalb overflows to infinity.
        return sign * Math.scalb((double) q, ulp);
    }
}
