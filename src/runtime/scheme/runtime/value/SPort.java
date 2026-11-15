package scheme.runtime.value;

import java.io.PrintStream;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;

/**
 * A port (R7RS §6.13). For now a textual output port: the console (the context's output, which goes
 * through the language Env, design.md D10) or a string port. Input and binary ports come with M4.
 */
public final class SPort implements TruffleObject {

    /** null for a string port. */
    private final PrintStream console;
    /** null for the console. */
    private final StringBuilder buffer;
    private boolean open = true;

    private SPort(PrintStream console, StringBuilder buffer) {
        this.console = console;
        this.buffer = buffer;
    }

    public static SPort console(PrintStream out) {
        return new SPort(out, null);
    }

    public static SPort outputString() {
        return new SPort(null, new StringBuilder());
    }

    /** No input ports yet (M4). */
    public boolean isInput() {
        return false;
    }

    public boolean isStringPort() {
        return buffer != null;
    }

    public boolean isOpen() {
        return open;
    }

    public void close() {
        open = false;
    }

    @TruffleBoundary
    public void write(String text) {
        if (console != null) {
            console.print(text);
        } else {
            buffer.append(text);
        }
    }

    @TruffleBoundary
    public void flush() {
        if (console != null) {
            console.flush();
        }
    }

    /** A string port's characters so far. */
    @TruffleBoundary
    public String contents() {
        return buffer.toString();
    }
}
