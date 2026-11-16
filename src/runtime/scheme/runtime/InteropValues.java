package scheme.runtime;

import java.math.BigInteger;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.strings.TruffleString;

import scheme.runtime.printer.Printer;
import scheme.runtime.value.SBigInteger;
import scheme.runtime.value.SChar;
import scheme.runtime.value.SString;

/**
 * EXPLORATION (docs/interop-and-tools-exploration.md): the boundary between Scheme values and
 * interop values (design.md D10).
 *
 * <p>Outward, nothing needs converting: every Scheme value is already an interop value ({@code long},
 * {@code double}, {@code boolean}, or a {@link TruffleObject} that exports {@link InteropLibrary} as
 * what it is). Inward, the interop primitives other languages and the host pass (boxed
 * {@code Integer}, {@code Float}, {@code Character}, {@code String}, {@code TruffleString}, ...) become
 * the one Scheme representation of each (design.md D1); any other foreign object stays foreign.
 */
public final class InteropValues {

    private InteropValues() {
    }

    /** The Scheme value for an interop value that came from outside: see the class comment. */
    public static Object toScheme(Object value) {
        if (value instanceof Long || value instanceof Double || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Integer i) {
            return (long) i;
        }
        if (value instanceof TruffleObject) {
            return value;
        }
        return primitiveToScheme(value);
    }

    @TruffleBoundary
    private static Object primitiveToScheme(Object value) {
        if (value instanceof Byte || value instanceof Short) {
            return ((Number) value).longValue();
        }
        if (value instanceof Float f) {
            return (double) f;
        }
        if (value instanceof Character c) {
            return SChar.of(c);
        }
        if (value instanceof String s) {
            return SString.of(s);
        }
        if (value instanceof TruffleString ts) {
            return SString.of(ts.toJavaStringUncached());
        }
        throw new IllegalArgumentException("not an interop value: " + value.getClass().getName());
    }

    /**
     * Like {@link #toScheme}, and a foreign number or string (a host {@code BigInteger}, a boxed host
     * string) becomes the Scheme number or string too. For the results of host calls.
     */
    @TruffleBoundary
    public static Object toSchemeDeep(Object value) {
        Object v = toScheme(value);
        if (v instanceof TruffleObject && !isSchemeValue(v)) {
            InteropLibrary interop = InteropLibrary.getUncached(v);
            try {
                if (interop.isNumber(v) && interop.fitsInLong(v)) {
                    return interop.asLong(v);
                }
                if (interop.isNumber(v) && interop.fitsInBigInteger(v)) {
                    BigInteger big = interop.asBigInteger(v);
                    return SBigInteger.normalize(big);
                }
                if (interop.isNumber(v) && interop.fitsInDouble(v)) {
                    return interop.asDouble(v);
                }
                if (interop.isString(v)) {
                    return SString.of(interop.asString(v));
                }
                if (interop.isBoolean(v)) {
                    return interop.asBoolean(v);
                }
            } catch (UnsupportedMessageException e) {
                throw new IllegalStateException(e);
            }
        }
        return v;
    }

    /** Whether the value is one of ours (any Scheme value), as opposed to a foreign object. */
    @TruffleBoundary
    public static boolean isSchemeValue(Object value) {
        if (value instanceof Long || value instanceof Double || value instanceof Boolean) {
            return true;
        }
        InteropLibrary interop = InteropLibrary.getUncached(value);
        try {
            return interop.hasLanguageId(value) && SchemeLanguage.ID.equals(interop.getLanguageId(value));
        } catch (UnsupportedMessageException e) {
            return false;
        }
    }

    /** toDisplayString for every Scheme value: what {@code write} prints (design.md D9). */
    @TruffleBoundary
    public static String display(Object value) {
        return Printer.print(value, Printer.Mode.WRITE);
    }

    /** How Scheme prints a foreign object: {@code #<foreign} its own language's display string{@code >}. */
    @TruffleBoundary
    public static String displayForeign(Object foreignDisplayString) {
        try {
            return "#<foreign " + InteropLibrary.getUncached().asString(foreignDisplayString) + ">";
        } catch (UnsupportedMessageException e) {
            return "#<foreign>";
        }
    }

    /** How Scheme prints a foreign object (the printer's fallback). */
    @TruffleBoundary
    public static String printForeign(Object value) {
        return displayForeign(InteropLibrary.getUncached().toDisplayString(value, true));
    }
}
