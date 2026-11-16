package scheme.runtime.value;

import java.math.BigInteger;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
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

    // EXPLORATION (design.md D10): a Scheme value to interop, shown as write prints it.

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
