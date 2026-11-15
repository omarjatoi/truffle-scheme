package scheme.runtime.value;

import java.math.BigDecimal;
import java.math.MathContext;

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

    /** The nearest double to an exact real (rationals: correctly rounded via 40-digit division). */
    @TruffleBoundary
    public static double toDouble(Object real) {
        if (real instanceof Double d) {
            return d;
        } else if (real instanceof Long l) {
            return l;
        } else if (real instanceof SBigInteger b) {
            return b.value.doubleValue();
        } else if (real instanceof SRational r) {
            return new BigDecimal(r.numerator).divide(new BigDecimal(r.denominator), new MathContext(40)).doubleValue();
        }
        throw new IllegalArgumentException("not a real number: " + real);
    }
}
