package scheme.runtime.value;

import java.util.HashMap;
import java.util.Map;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;

/** Interns symbols. One table per context (design.md D1); contexts are single-threaded (D10). */
public final class SymbolTable {

    private final Map<String, SSymbol> symbols = new HashMap<>();

    @TruffleBoundary
    public SSymbol intern(String name) {
        return symbols.computeIfAbsent(name, n -> new SSymbol(TruffleString.fromJavaStringUncached(n, TruffleString.Encoding.UTF_8)));
    }
}
