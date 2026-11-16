package scheme.runtime.value;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import scheme.runtime.InteropValues;
import scheme.runtime.SchemeLanguage;

/**
 * A non-real complex number in rectangular form. Both parts are real numbers of the same exactness:
 * mixing exactness makes both inexact, and an exact zero imaginary part makes the number real
 * (design.md D1). An inexact zero imaginary part stays complex.
 */
@ExportLibrary(InteropLibrary.class)
public final class SComplex implements TruffleObject {

    public final Object real;
    public final Object imag;

    private SComplex(Object real, Object imag) {
        this.real = real;
        this.imag = imag;
    }

    /** The canonical number {@code real + imag*i}. Both arguments must be real numbers. */
    @TruffleBoundary
    public static Object make(Object real, Object imag) {
        boolean realExact = Numbers.isExact(real);
        boolean imagExact = Numbers.isExact(imag);
        if (imagExact && Numbers.isExactZero(imag)) {
            return real;
        }
        if (realExact != imagExact) {
            return new SComplex(Numbers.toDouble(real), Numbers.toDouble(imag));
        }
        return new SComplex(real, imag);
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
