package scheme.builtins;

import java.util.List;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.runtime.PrimitiveNode;
import scheme.runtime.PrimitiveSpec;
import scheme.runtime.SchemeContext;
import scheme.runtime.SchemeError;
import scheme.runtime.printer.Printer;
import scheme.runtime.value.SChar;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SPort;
import scheme.runtime.value.SString;

/**
 * The input and output area (R7RS §6.13): its primitives, all leaves (AGENTS.md rule 1). One of the
 * fan-out areas (AGENTS.md "Areas"): add this area's primitives here and nowhere else. Output takes
 * an explicit port; io.scm supplies current-output-port when it is left out.
 */
final class IoPrimitives {

    private IoPrimitives() {
    }

    /** This area's primitives: the one list to add to. */
    static List<PrimitiveSpec> primitives() {
        return List.of(
                        new PrimitiveSpec("%stdout-port", 0, 0, StdoutPort::new),
                        new PrimitiveSpec("%display", 2, 2, () -> new Print(Printer.Mode.DISPLAY)),
                        new PrimitiveSpec("%write", 2, 2, () -> new Print(Printer.Mode.WRITE)),
                        new PrimitiveSpec("%write-shared", 2, 2, () -> new Print(Printer.Mode.WRITE_SHARED)),
                        new PrimitiveSpec("%write-simple", 2, 2, () -> new Print(Printer.Mode.WRITE_SIMPLE)),
                        new PrimitiveSpec("%write-string", 2, 2, WriteString::new),
                        new PrimitiveSpec("%write-char", 2, 2, WriteChar::new),
                        new PrimitiveSpec("%flush-output-port", 1, 1, Flush::new),
                        new PrimitiveSpec("open-output-string", 0, 0, OpenOutputString::new),
                        new PrimitiveSpec("get-output-string", 1, 1, GetOutputString::new),
                        new PrimitiveSpec("port?", 1, 1, () -> new TypePredicate(x -> x instanceof SPort)),
                        new PrimitiveSpec("output-port?", 1, 1, () -> new TypePredicate(x -> x instanceof SPort)),
                        new PrimitiveSpec("textual-port?", 1, 1, () -> new TypePredicate(x -> x instanceof SPort)),
                        new PrimitiveSpec("input-port?", 1, 1, () -> new TypePredicate(x -> x instanceof SPort p && p.isInput())),
                        new PrimitiveSpec("output-port-open?", 1, 1, () -> new TypePredicate(x -> x instanceof SPort p && p.isOpen())),
                        new PrimitiveSpec("close-port", 1, 1, ClosePort::new),
                        new PrimitiveSpec("close-output-port", 1, 1, ClosePort::new));
    }

    static SPort port(String who, Object x) {
        if (x instanceof SPort p) {
            if (!p.isOpen()) {
                throw closed(who);
            }
            return p;
        }
        throw ControlPrimitives.notA(who, "port", x);
    }

    @TruffleBoundary
    private static SchemeError closed(String who) {
        return new SchemeError(who + ": port is closed");
    }

    static final class StdoutPort extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return SchemeContext.get(this).stdoutPort();
        }
    }

    /** {@code (%display obj port)}, {@code %write}, {@code %write-shared}, {@code %write-simple}. */
    static final class Print extends PrimitiveNode {
        private final Printer.Mode mode;

        Print(Printer.Mode mode) {
            this.mode = mode;
        }

        @Override
        public Object execute(Object[] args) {
            print(port("write", args[1]), mode, args[0]);
            return SConstant.UNSPECIFIED;
        }

        @TruffleBoundary
        private static void print(SPort port, Printer.Mode mode, Object value) {
            port.write(Printer.print(value, mode));
        }
    }

    static final class WriteString extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            if (!(args[0] instanceof SString s)) {
                throw ControlPrimitives.notA("write-string", "string", args[0]);
            }
            port("write-string", args[1]).write(s.toString());
            return SConstant.UNSPECIFIED;
        }
    }

    static final class WriteChar extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            if (!(args[0] instanceof SChar c)) {
                throw ControlPrimitives.notA("write-char", "character", args[0]);
            }
            port("write-char", args[1]).write(Character.toString(c.codePoint));
            return SConstant.UNSPECIFIED;
        }
    }

    static final class Flush extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            port("flush-output-port", args[0]).flush();
            return SConstant.UNSPECIFIED;
        }
    }

    static final class OpenOutputString extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return SPort.outputString();
        }
    }

    static final class GetOutputString extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            if (!(args[0] instanceof SPort p) || !p.isStringPort()) {
                throw ControlPrimitives.notA("get-output-string", "string output port", args[0]);
            }
            return SString.of(p.contents());
        }
    }

    static final class ClosePort extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            if (!(args[0] instanceof SPort p)) {
                throw ControlPrimitives.notA("close-port", "port", args[0]);
            }
            p.close();
            return SConstant.UNSPECIFIED;
        }
    }
}
