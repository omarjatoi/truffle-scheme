package scheme.builtins;

import java.util.List;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.runtime.PrimitiveNode;
import scheme.runtime.PrimitiveSpec;
import scheme.runtime.SchemeContext;
import scheme.runtime.printer.Printer;
import scheme.runtime.value.SConstant;

/**
 * The input and output area (R7RS §6.13): its primitives, all leaves (AGENTS.md rule 1). One of the
 * fan-out areas (AGENTS.md "Areas"): add this area's primitives here and nowhere else.
 */
final class IoPrimitives {

    private IoPrimitives() {
    }

    /** This area's primitives: the one list to add to. */
    static List<PrimitiveSpec> primitives() {
        return List.of(
                        new PrimitiveSpec("display", 1, 1, () -> new Output(Printer.Mode.DISPLAY)),
                        new PrimitiveSpec("write", 1, 1, () -> new Output(Printer.Mode.WRITE)),
                        new PrimitiveSpec("newline", 0, 0, () -> new Output(null)));
    }

    /** display, write and newline on the current output port (ports are M4). */
    static final class Output extends PrimitiveNode {
        /** null for newline. */
        private final Printer.Mode mode;

        Output(Printer.Mode mode) {
            this.mode = mode;
        }

        @Override
        public Object execute(Object[] args) {
            print(SchemeContext.get(this), mode, mode == null ? null : args[0]);
            return SConstant.UNSPECIFIED;
        }

        @TruffleBoundary
        private static void print(SchemeContext context, Printer.Mode mode, Object value) {
            if (mode == null) {
                context.out().print('\n');
            } else {
                context.out().print(Printer.print(value, mode));
            }
            context.out().flush();
        }
    }
}
