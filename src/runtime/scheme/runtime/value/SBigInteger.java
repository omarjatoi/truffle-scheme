package scheme.runtime.value;

import java.math.BigDecimal;
import java.math.BigInteger;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import scheme.runtime.InteropValues;
import scheme.runtime.SchemeLanguage;

/**
 * An exact integer outside the {@code long} range. Results that fit in a {@code long} are always
 * demoted, so each integer has exactly one representation (design.md D1).
 */
@ExportLibrary(InteropLibrary.class)
public final class SBigInteger implements TruffleObject {

    public final BigInteger value;

    private SBigInteger(BigInteger value) {
        this.value = value;
    }

    /** The canonical exact integer for {@code value}: a {@code Long} if it fits, else an SBigInteger. */
    @TruffleBoundary
    public static Object normalize(BigInteger value) {
        return value.bitLength() < Long.SIZE ? (Object) value.longValue() : new SBigInteger(value);
    }

    @Override
    @TruffleBoundary
    public String toString() {
        return value.toString();
    }

    // A number to interop (design.md D10): never a long (it would have been demoted), a big integer
    // always, and a float or double only when the conversion is exact.

    @ExportMessage
    boolean isNumber() {
        return true;
    }

    @ExportMessage
    boolean fitsInByte() {
        return false;
    }

    @ExportMessage
    boolean fitsInShort() {
        return false;
    }

    @ExportMessage
    boolean fitsInInt() {
        return false;
    }

    @ExportMessage
    boolean fitsInLong() {
        return false;
    }

    @ExportMessage
    byte asByte() throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    @ExportMessage
    short asShort() throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    @ExportMessage
    int asInt() throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    @ExportMessage
    long asLong() throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    @ExportMessage
    @TruffleBoundary
    float asFloat() throws UnsupportedMessageException {
        if (!fitsInFloat()) {
            throw UnsupportedMessageException.create();
        }
        return (float) Numbers.toDouble(this);
    }

    @ExportMessage
    @TruffleBoundary
    double asDouble() throws UnsupportedMessageException {
        if (!fitsInDouble()) {
            throw UnsupportedMessageException.create();
        }
        return Numbers.toDouble(this);
    }

    @ExportMessage
    boolean fitsInBigInteger() {
        return true;
    }

    @ExportMessage
    BigInteger asBigInteger() {
        return value;
    }

    @ExportMessage
    @TruffleBoundary
    boolean fitsInFloat() {
        float f = value.floatValue();
        return !Float.isInfinite(f) && new BigDecimal(f).toBigInteger().equals(value);
    }

    @ExportMessage
    @TruffleBoundary
    boolean fitsInDouble() {
        double d = value.doubleValue();
        return !Double.isInfinite(d) && new BigDecimal(d).toBigInteger().equals(value);
    }

    // Interop (design.md D10).

    @ExportMessage
    boolean hasLanguageId() {
        return true;
    }

    @ExportMessage
    String getLanguageId() {
        return SchemeLanguage.ID;
    }

    @ExportMessage
    boolean hasMetaObject() {
        return true;
    }

    @ExportMessage
    Object getMetaObject() {
        return SchemeType.NUMBER;
    }

    @ExportMessage
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return InteropValues.display(this);
    }
}
