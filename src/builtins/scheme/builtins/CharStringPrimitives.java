package scheme.builtins;

import java.util.List;

import scheme.runtime.PrimitiveSpec;
import scheme.runtime.value.SString;
import scheme.runtime.value.SSymbol;

/**
 * The characters, strings and symbols area (R7RS §6.5–6.7): its primitives, all leaves (AGENTS.md rule 1). One of the
 * fan-out areas (AGENTS.md "Areas"): add this area's primitives here and nowhere else.
 */
final class CharStringPrimitives {

    private CharStringPrimitives() {
    }

    /** This area's primitives: the one list to add to. */
    static List<PrimitiveSpec> primitives() {
        return List.of(
                        new PrimitiveSpec("symbol?", 1, 1, () -> new TypePredicate(x -> x instanceof SSymbol)),
                        new PrimitiveSpec("string?", 1, 1, () -> new TypePredicate(x -> x instanceof SString)));
    }
}
