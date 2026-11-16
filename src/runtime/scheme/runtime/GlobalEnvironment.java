package scheme.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.runtime.value.SSymbol;

/**
 * A namespace of global cells (design.md D7): a library's, the interaction environment's, or the
 * primitives'. Cells are created on first reference, so a library can export or refer to a name it
 * defines later (or never: the cell stays unbound).
 */
public final class GlobalEnvironment {

    private final Map<SSymbol, GlobalCell> cells = new LinkedHashMap<>();
    /** Cells that no name refers to: macro-introduced top-level definitions (design.md D16). */
    private final Set<GlobalCell> hidden = Collections.newSetFromMap(new IdentityHashMap<>());
    /** {@link #hidden}, in order of creation. */
    private final List<GlobalCell> hiddenInOrder = new ArrayList<>();

    @TruffleBoundary
    public GlobalCell cell(SSymbol name) {
        return cells.computeIfAbsent(name, GlobalCell::new);
    }

    /**
     * A new cell of this namespace that its name doesn't refer to (design.md D16): only the
     * expander's binding, for the scopes of the identifier that defined it, does.
     */
    @TruffleBoundary
    public GlobalCell freshCell(SSymbol name) {
        GlobalCell cell = new GlobalCell(name);
        hidden.add(cell);
        hiddenInOrder.add(cell);
        return cell;
    }

    /** Whether the cell is this namespace's own (not imported). */
    @TruffleBoundary
    public boolean owns(GlobalCell cell) {
        return cells.get(cell.name) == cell || hidden.contains(cell);
    }

    /** Every cell that a name refers to, in order of creation. */
    @TruffleBoundary
    public Collection<GlobalCell> cells() {
        return cells.values();
    }

    /** Every cell that no name refers to ({@link #freshCell}), in order of creation. */
    @TruffleBoundary
    public List<GlobalCell> hiddenCells() {
        return Collections.unmodifiableList(hiddenInOrder);
    }
}
