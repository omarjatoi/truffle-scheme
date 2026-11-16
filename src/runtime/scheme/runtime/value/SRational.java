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
import scheme.runtime.SchemeError;
import scheme.runtime.SchemeLanguage;

/**
 * A non-integer exact rational: numerator and denominator in lowest terms, denominator above 1
 * (design.md D1).
 */
@ExportLibrary(InteropLibrary.class)
public final class SRational implements TruffleObject {

    public final BigInteger numerator;
    public final BigInteger denominator;

    private SRational(BigInteger numerator, BigInteger denominator) {
        this.numerator = numerator;
        this.denominator = denominator;
    }

    /** The canonical exact number {@code numerator/denominator}: an exact integer if it is one. */
    @TruffleBoundary
    public static Object make(BigInteger numerator, BigInteger denominator) {
        if (denominator.signum() == 0) {
            throw new SchemeError("division by zero");
        }
        if (denominator.signum() < 0) {
            numerator = numerator.negate();
            denominator = denominator.negate();
        }
        BigInteger gcd = numerator.gcd(denominator);
        if (!gcd.equals(BigInteger.ONE) && gcd.signum() != 0) {
            numerator = numerator.divide(gcd);
            denominator = denominator.divide(gcd);
        }
        if (denominator.equals(BigInteger.ONE)) {
            return SBigInteger.normalize(numerator);
        }
        return new SRational(numerator, denominator);
    }

    @Override
    @TruffleBoundary
    public String toString() {
        return numerator + "/" + denominator;
    }

    // A number to interop (design.md D10): not an integer of any size, and a float or double only
    // when the conversion is exact (1/2, not 1/3).

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
        return false;
    }

    @ExportMessage
    BigInteger asBigInteger() throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    @ExportMessage
    @TruffleBoundary
    boolean fitsInFloat() {
        double d = Numbers.toDouble(this);
        return isExactly((float) d);
    }

    @ExportMessage
    @TruffleBoundary
    boolean fitsInDouble() {
        return isExactly(Numbers.toDouble(this));
    }

    /** Whether d is exactly this rational (d's exact value times the denominator is the numerator). */
    private boolean isExactly(double d) {
        return !Double.isInfinite(d) && new BigDecimal(d).multiply(new BigDecimal(denominator)).compareTo(new BigDecimal(numerator)) == 0;
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
