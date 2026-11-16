package scheme.builtins;

import scheme.runtime.PrimitiveNode;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

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
                        new PrimitiveSpec("string?", 1, 1, () -> new TypePredicate(x -> x instanceof SString)),
                        new PrimitiveSpec("string-append", 0, -1, StringAppend::new));
    }

    /** {@code (string-append string ...)}: a new mutable string. */
    static final class StringAppend extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return append(args);
        }

        @TruffleBoundary
        private static SString append(Object[] args) {
            StringBuilder text = new StringBuilder();
            for (Object a : args) {
                if (!(a instanceof SString s)) {
                    throw ControlPrimitives.notA("string-append", "string", a);
                }
                text.append(s.toString());
            }
            return SString.of(text.toString());
        }
    }
}
