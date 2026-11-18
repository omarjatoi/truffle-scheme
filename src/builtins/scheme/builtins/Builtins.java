package scheme.builtins;

import java.util.ArrayList;
import java.util.List;

import scheme.runtime.BuiltinLibrary;
import scheme.runtime.PrimitiveSpec;

/**
 * Every primitive, from the fan-out areas (AGENTS.md "Areas"), as the internal library
 * {@code (r7rs %primitives)} (design.md D7). Fixed: an area adds its primitives to its own file,
 * never here. Every one is a leaf: none calls Scheme (AGENTS.md rule 1).
 */
public final class Builtins implements BuiltinLibrary {

    @Override
    public List<PrimitiveSpec> primitives() {
        List<PrimitiveSpec> all = new ArrayList<>();
        all.addAll(NumberPrimitives.primitives());
        all.addAll(CharStringPrimitives.primitives());
        all.addAll(ListVectorPrimitives.primitives());
        all.addAll(BytevectorPrimitives.primitives());
        all.addAll(ControlPrimitives.primitives());
        all.addAll(IoPrimitives.primitives());
        all.addAll(EvalPrimitives.primitives());
        all.addAll(ProcessTimePrimitives.primitives());
        // Not an area: (graalvm polyglot)'s %-names (design.md D20).
        all.addAll(PolyglotPrimitives.primitives());
        return all;
    }
}
