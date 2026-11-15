package scheme.runtime;

import java.util.HashMap;
import java.util.Map;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.runtime.value.SSymbol;

/** The global cells of one context. Libraries (M3) will map exported names to cells. */
public final class GlobalEnvironment {

    private final Map<SSymbol, GlobalCell> cells = new HashMap<>();

    @TruffleBoundary
    public GlobalCell cell(SSymbol name) {
        return cells.computeIfAbsent(name, GlobalCell::new);
    }
}
