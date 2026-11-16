package scheme.runtime.value;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.strings.TruffleString;

import scheme.runtime.InteropValues;
import scheme.runtime.SchemeLanguage;

/** A symbol. Symbols are interned per context ({@link SymbolTable}), so {@code eq?} is identity. */
@ExportLibrary(InteropLibrary.class)
public final class SSymbol implements TruffleObject {

    public final TruffleString name;

    SSymbol(TruffleString name) {
        this.name = name;
    }

    @Override
    @TruffleBoundary
    public String toString() {
        return name.toJavaStringUncached();
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
        return SchemeType.SYMBOL;
    }

    @ExportMessage
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return InteropValues.display(this);
    }
}
