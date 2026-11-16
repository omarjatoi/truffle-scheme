package scheme.runtime;

import java.math.BigInteger;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.HeapIsolationException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.strings.TruffleString;

import scheme.runtime.printer.Printer;
import scheme.runtime.value.SBigInteger;
import scheme.runtime.value.SChar;
import scheme.runtime.value.SString;
import scheme.runtime.value.SchemeType;

/**
 * The boundary between Scheme values and interop values (design.md D10).
 *
 * <p>Outward nothing is converted: every Scheme value already is an interop value ({@code long},
 * {@code double}, {@code boolean}, or a TruffleObject that exports {@link InteropLibrary} as what
 * it is). Inward, what Java or another language passes (procedure arguments, array elements,
 * bindings) becomes the one Scheme representation of it (D1): interop's primitives, and foreign
 * numbers, strings and booleans (a host {@code BigInteger}, say). Anything else, a foreign null
 * included, stays a foreign object: Scheme can store it, return it and compare it with {@code eq?},
 * and a primitive that gets one raises a Scheme error (D8). A Java object is the same Scheme object
 * each time it comes in ({@link HostObjects}).
 */
public final class InteropValues {

    private InteropValues() {
    }

    /** The Scheme value for an interop value that came from outside: see the class comment. */
    public static Object toScheme(Object value) throws UnsupportedTypeException {
        if (value instanceof Long || value instanceof Double || value instanceof Boolean) {
            return value;
        }
        return toSchemeSlow(value);
    }

    @TruffleBoundary
    private static Object toSchemeSlow(Object value) throws UnsupportedTypeException {
        if (value instanceof Character c && Character.isSurrogate(c)) {
            throw UnsupportedTypeException.create(new Object[]{value}, "a lone surrogate is not a Scheme character");
        }
        Object primitive = toSchemePrimitive(value);
        if (primitive != value || isSchemeValue(value)) {
            return primitive;
        }
        Object v = value instanceof LanguageView view ? view.delegate : value;
        InteropLibrary interop = InteropLibrary.getUncached(v);
        try {
            if (interop.isBoolean(v)) {
                return interop.asBoolean(v);
            }
            if (interop.isString(v)) {
                return SString.of(interop.asString(v));
            }
            if (interop.isNumber(v)) {
                if (interop.fitsInLong(v)) {
                    return interop.asLong(v);
                }
                if (interop.fitsInBigInteger(v)) {
                    BigInteger big = interop.asBigInteger(v);
                    return SBigInteger.normalize(big);
                }
                if (interop.fitsInDouble(v)) {
                    return interop.asDouble(v);
                }
            }
            if (interop.isHostObject(v)) {
                // Wrapped afresh at each crossing: the same Java object is the same Scheme object.
                return SchemeContext.get(null).hostObjects.canonical(v, interop.asHostObject(v));
            }
        } catch (UnsupportedMessageException e) {
            // The value said it fits, then refused: a bug in the other language.
            throw new IllegalStateException(e);
        } catch (HeapIsolationException e) {
            // A host object in another heap: no Java object to key it on here.
            return v;
        }
        return v;
    }

    /**
     * Interop's primitive values as Scheme values: other boxed numbers become {@code long} or
     * {@code double}, a {@code char} a Scheme character, a string a fresh mutable Scheme string.
     * Anything else is returned as it is.
     */
    @TruffleBoundary
    public static Object toSchemePrimitive(Object value) {
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        } else if (value instanceof Float f) {
            return (double) f;
        } else if (value instanceof Character c && !Character.isSurrogate(c)) {
            return SChar.of(c);
        } else if (value instanceof String s) {
            return SString.of(s);
        } else if (value instanceof TruffleString ts) {
            return SString.of(ts.toJavaStringUncached());
        }
        return value;
    }

    /** Whether the value is a Scheme value (D1), as opposed to a foreign one. */
    public static boolean isSchemeValue(Object value) {
        return SchemeType.of(value) != null || value instanceof Continuation || value instanceof DynEnv;
    }

    /** toDisplayString for every Scheme value: what {@code write} prints (design.md D9). */
    @TruffleBoundary
    public static String display(Object value) {
        return Printer.print(value, Printer.Mode.WRITE);
    }

    /** How Scheme writes a foreign value: {@code #<foreign} its own display string{@code >}. */
    @TruffleBoundary
    public static String displayForeign(Object value) {
        InteropLibrary interop = InteropLibrary.getUncached();
        try {
            return "#<foreign " + interop.asString(interop.toDisplayString(value, true)) + ">";
        } catch (UnsupportedMessageException e) {
            return "#<foreign>";
        }
    }
}
