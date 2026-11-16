package scheme.runtime;

import java.io.PrintStream;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;

import scheme.runtime.printer.Printer;
import scheme.runtime.value.MultipleValues;
import scheme.runtime.value.SConstant;

/**
 * Runs an interactive source (a REPL input, design.md D17) and writes each of its values, one per
 * line, the way {@code write} does, to the context's standard output. An unspecified value prints
 * nothing. The language prints these itself ({@link SchemeLanguage#isVisible} is false), so that
 * printing doesn't depend on interop's display strings. Hand-written: it is a driver, not Scheme
 * code.
 */
final class InteractiveRootNode extends RootNode {

    private static final Object[] NO_ARGUMENTS = new Object[0];

    @Child private DirectCallNode program;

    InteractiveRootNode(SchemeLanguage language, CallTarget program) {
        super(language);
        this.program = DirectCallNode.create(program);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object result = program.call(NO_ARGUMENTS);
        print(SchemeContext.get(this).out(), result);
        return result;
    }

    @TruffleBoundary
    private static void print(PrintStream out, Object result) {
        if (result instanceof MultipleValues values) {
            for (Object value : values.values) {
                out.print(Printer.print(value, Printer.Mode.WRITE) + "\n");
            }
        } else if (result != SConstant.UNSPECIFIED) {
            out.print(Printer.print(result, Printer.Mode.WRITE) + "\n");
        }
        out.flush();
    }

    @Override
    public boolean isInternal() {
        return true;
    }

    @Override
    public String getName() {
        return "<interactive>";
    }

    @Override
    public String toString() {
        return "<interactive>";
    }
}
