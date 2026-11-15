package scheme.runtime;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.runtime.value.SSymbol;

/**
 * A namespace of global cells (design.md D7): a library's, the interaction environment's, or the
 * primitives'. Cells are created on first reference, so a library can export or refer to a name it
 * defines later (or never: the cell stays unbound).
 */
public final class GlobalEnvironment {

    private final Map<SSymbol, GlobalCell> cells = new LinkedHashMap<>();

    @TruffleBoundary
    public GlobalCell cell(SSymbol name) {
        return cells.computeIfAbsent(name, GlobalCell::new);
    }

    /** Whether the cell is this namespace's own (not imported). */
    @TruffleBoundary
    public boolean owns(GlobalCell cell) {
        return cells.get(cell.name) == cell;
    }

    /** Every cell, in order of creation. */
    @TruffleBoundary
    public Collection<GlobalCell> cells() {
        return cells.values();
    }
}
