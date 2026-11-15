package scheme.runtime.value;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.strings.TruffleString;

/** A symbol. Symbols are interned per context ({@link SymbolTable}), so {@code eq?} is identity. */
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
}
