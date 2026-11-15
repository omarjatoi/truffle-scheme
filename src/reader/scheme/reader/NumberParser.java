package scheme.reader;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.runtime.SchemeError;
import scheme.runtime.value.Numbers;
import scheme.runtime.value.SBigInteger;
import scheme.runtime.value.SComplex;
import scheme.runtime.value.SRational;

/**
 * Parses R7RS number syntax (section 7.1.1) into the D1 value types: radix and exactness prefixes in
 * either order, integers, rationals, decimals with exponents (also the R5RS markers s, f, d and l,
 * which the vendored suite uses), {@code +inf.0}/{@code +nan.0}, rectangular {@code a+bi} and polar
 * {@code r@θ}. Used by the reader and, later, {@code string->number}.
 */
public final class NumberParser {

    private NumberParser() {
    }

    /** The number {@code text} denotes, or null if it isn't valid number syntax. */
    @TruffleBoundary
    public static Object parse(String text, int defaultRadix) {
        int radix = 0;
        char exactness = 0;
        int i = 0;
        while (i + 1 < text.length() && text.charAt(i) == '#') {
            char c = Character.toLowerCase(text.charAt(i + 1));
            if (c == 'e' || c == 'i') {
                if (exactness != 0) {
                    return null;
                }
                exactness = c;
            } else if (c == 'x' || c == 'd' || c == 'o' || c == 'b') {
                if (radix != 0) {
                    return null;
                }
                radix = c == 'x' ? 16 : c == 'd' ? 10 : c == 'o' ? 8 : 2;
            } else {
                return null;
            }
            i += 2;
        }
        String body = text.substring(i);
        if (body.isEmpty()) {
            return null;
        }
        try {
            return complex(body, radix == 0 ? defaultRadix : radix, exactness);
        } catch (SchemeError e) {
            // e.g. an exact rational with a zero denominator
            return null;
        }
    }

    private static Object complex(String s, int radix, char exactness) {
        int at = s.indexOf('@');
        if (at >= 0) {
            Object magnitude = real(s.substring(0, at), radix, exactness);
            Object angle = real(s.substring(at + 1), radix, exactness);
            if (magnitude == null || angle == null) {
                return null;
            }
            if (Numbers.isExactZero(angle)) {
                return magnitude;
            }
            double r = Numbers.toDouble(magnitude);
            double theta = Numbers.toDouble(angle);
            return SComplex.make(r * Math.cos(theta), r * Math.sin(theta));
        }
        char last = Character.toLowerCase(s.charAt(s.length() - 1));
        if (last != 'i') {
            return real(s, radix, exactness);
        }
        // a+bi, a-bi, +bi, -bi, +i, -i: try each sign as the start of the imaginary part, rightmost first.
        for (int p = s.length() - 2; p >= 0; p--) {
            char c = s.charAt(p);
            if (c != '+' && c != '-') {
                continue;
            }
            String imagText = s.substring(p, s.length() - 1);
            Object imag = imagText.length() == 1 ? exact(c == '+' ? 1L : -1L, exactness) : real(imagText, radix, exactness);
            if (imag == null) {
                continue;
            }
            Object re = p == 0 ? exact(0L, exactness) : real(s.substring(0, p), radix, exactness);
            if (re == null) {
                continue;
            }
            return SComplex.make(re, imag);
        }
        return null;
    }

    private static Object exact(long value, char exactness) {
        return exactness == 'i' ? (Object) (double) value : (Object) value;
    }

    /** A signed real: [+-]ureal or an infnan. */
    private static Object real(String s, int radix, char exactness) {
        String lower = s.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "+inf.0":
                return exactness == 'e' ? null : Double.POSITIVE_INFINITY;
            case "-inf.0":
                return exactness == 'e' ? null : Double.NEGATIVE_INFINITY;
            case "+nan.0", "-nan.0":
                return exactness == 'e' ? null : Double.NaN;
            default:
                break;
        }
        boolean negative = false;
        String u = s;
        if (!u.isEmpty() && (u.charAt(0) == '+' || u.charAt(0) == '-')) {
            negative = u.charAt(0) == '-';
            u = u.substring(1);
        }
        if (u.isEmpty()) {
            return null;
        }
        int slash = u.indexOf('/');
        if (slash >= 0) {
            BigInteger n = uinteger(u.substring(0, slash), radix);
            BigInteger d = uinteger(u.substring(slash + 1), radix);
            if (n == null || d == null) {
                return null;
            }
            Object q = SRational.make(negative ? n.negate() : n, d);
            return exactness == 'i' ? (Object) Numbers.toDouble(q) : q;
        }
        BigInteger integer = uinteger(u, radix);
        if (integer != null) {
            Object value = SBigInteger.normalize(negative ? integer.negate() : integer);
            return exactness == 'i' ? (Object) Numbers.toDouble(value) : value;
        }
        if (radix != 10 || !isDecimal(u)) {
            return null;
        }
        String decimal = (negative ? "-" : "") + normalizeExponent(u);
        if (exactness == 'e') {
            BigDecimal bd = new BigDecimal(decimal);
            if (bd.scale() <= 0) {
                return SBigInteger.normalize(bd.toBigIntegerExact());
            }
            return SRational.make(bd.unscaledValue(), BigInteger.TEN.pow(bd.scale()));
        }
        return Double.parseDouble(decimal);
    }

    private static BigInteger uinteger(String s, int radix) {
        if (s.isEmpty()) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.digit(s.charAt(i), radix) < 0 || s.charAt(i) > 0x7f) {
                return null;
            }
        }
        return new BigInteger(s, radix);
    }

    private static boolean isExponentMarker(char c) {
        char lower = Character.toLowerCase(c);
        return lower == 'e' || lower == 's' || lower == 'f' || lower == 'd' || lower == 'l';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** digit+ [. digit*] [exponent] | . digit+ [exponent], with at least one digit before any exponent. */
    private static boolean isDecimal(String s) {
        int i = 0;
        int len = s.length();
        int digits = 0;
        while (i < len && isDigit(s.charAt(i))) {
            i++;
            digits++;
        }
        boolean point = false;
        if (i < len && s.charAt(i) == '.') {
            point = true;
            i++;
            while (i < len && isDigit(s.charAt(i))) {
                i++;
                digits++;
            }
        }
        if (digits == 0) {
            return false;
        }
        boolean exponent = false;
        if (i < len && isExponentMarker(s.charAt(i))) {
            exponent = true;
            i++;
            if (i < len && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                i++;
            }
            int expDigits = 0;
            while (i < len && isDigit(s.charAt(i))) {
                i++;
                expDigits++;
            }
            if (expDigits == 0) {
                return false;
            }
        }
        return i == len && (point || exponent);
    }

    private static String normalizeExponent(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(isExponentMarker(c) ? 'e' : c);
        }
        return sb.toString();
    }
}
