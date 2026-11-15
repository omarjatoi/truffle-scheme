package scheme.builtins;

import java.util.List;

import scheme.runtime.PrimitiveSpec;

/**
 * The process context and time area (R7RS §6.14): its primitives, all leaves (AGENTS.md rule 1). One of the
 * fan-out areas (AGENTS.md "Areas"): add this area's primitives here and nowhere else.
 */
final class ProcessTimePrimitives {

    private ProcessTimePrimitives() {
    }

    /** This area's primitives: the one list to add to. */
    static List<PrimitiveSpec> primitives() {
        return List.of();
    }
}
