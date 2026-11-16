package scheme.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import scheme.runtime.printer.Printer;
import scheme.runtime.value.SClosure;

/**
 * Returned from a tail call site instead of calling (design.md "Calls and tail calls"). The
 * trampoline at the nearest non-tail call site performs it. {@code arguments[0]} is the procedure.
 *
 * <p>It is what a procedure that ends in a tail call returns, so tools see it as that procedure's
 * return value (the debugger's, after stepping out of it). They show it as the call it stands for,
 * {@code #<tail call (g 2)>}, not as a foreign object (plan.md M6-05): the call's own value doesn't
 * exist yet.
 */
@ExportLibrary(InteropLibrary.class)
public final class TailCall implements TruffleObject {

    public final Object procedure;
    public final Object[] arguments;

    public TailCall(Object procedure, Object[] arguments) {
        this.procedure = procedure;
        this.arguments = arguments;
    }

    @ExportMessage
    boolean hasLanguageId() {
        return true;
    }

    @ExportMessage
    String getLanguageId() {
        return SchemeLanguage.ID;
    }

    @ExportMessage
    @TruffleBoundary
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        StringBuilder sb = new StringBuilder("#<tail call (");
        String name = procedure instanceof SClosure closure ? closure.getName() : null;
        sb.append(name != null ? name : Printer.print(procedure, Printer.Mode.WRITE));
        for (int i = 1; i < arguments.length; i++) {
            sb.append(' ').append(Printer.print(arguments[i], Printer.Mode.WRITE));
        }
        return sb.append(")>").toString();
    }
}
