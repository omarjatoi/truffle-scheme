package scheme.runtime.value;

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

    // EXPLORATION (design.md D10): a number to interop that never fits in a long (it would have been
    // demoted), and fits in a float or double only when the conversion is exact.

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
    boolean fitsInBigInteger() {
        return true;
    }

    @ExportMessage
    @TruffleBoundary
    boolean fitsInFloat() {
        float f = value.floatValue();
        return !Float.isInfinite(f) && new java.math.BigDecimal(f).toBigInteger().equals(value);
    }

    @ExportMessage
    @TruffleBoundary
    boolean fitsInDouble() {
        double d = value.doubleValue();
        return !Double.isInfinite(d) && new java.math.BigDecimal(d).toBigInteger().equals(value);
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
    BigInteger asBigInteger() {
        return value;
    }

    @ExportMessage
    @TruffleBoundary
    float asFloat() throws UnsupportedMessageException {
        if (!fitsInFloat()) {
            throw UnsupportedMessageException.create();
        }
        return value.floatValue();
    }

    @ExportMessage
    @TruffleBoundary
    double asDouble() throws UnsupportedMessageException {
        if (!fitsInDouble()) {
            throw UnsupportedMessageException.create();
        }
        return value.doubleValue();
    }

    @ExportMessage
    boolean hasLanguageId() {
        return true;
    }

    @ExportMessage
    String getLanguageId() {
        return SchemeLanguage.ID;
    }

    @ExportMessage
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return InteropValues.display(this);
    }
}
