package scheme.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import scheme.runtime.printer.Printer;
import scheme.runtime.value.SClosure;

/**
 * A tail call's request (design.md "Calls and tail calls"): what a procedure that ends in a tail
 * call returns, for the trampoline it returns to (a non-tail call site's or a prompt's) to
 * perform. {@code arguments[0]} is the procedure.
 *
 * <p>A tail call a procedure makes is requested by its argument array alone while no tool is
 * attached (design.md "Calls Truffle doesn't inline"): where the request crosses a call Truffle
 * doesn't inline, it is then one allocation, not two. With a tool attached ({@code noTags}
 * invalid), and from the primitives that request one ({@code apply}, {@code eval}), it is a
 * {@code TailCall}: tools see it as that procedure's return value (the debugger's, after stepping
 * out of it), and show it as the call it stands for, {@code #<tail call (g 2)>}, not as a foreign
 * object (plan.md M6-05): the call's own value doesn't exist yet. An {@code Object[]} is never a
 * Scheme value, so the two can't be confused with one.
 */
@ExportLibrary(InteropLibrary.class)
public final class TailCall implements TruffleObject {

    public final Object procedure;
    public final Object[] arguments;

    public TailCall(Object procedure, Object[] arguments) {
        this.procedure = procedure;
        this.arguments = arguments;
    }

    /**
     * The arguments of the tail call {@code value} requests, element 0 the procedure (a
     * {@code TailCall}'s, or the argument array that requests it alone); null if {@code value} is a
     * value.
     */
    public static Object[] argumentsOf(Object value) {
        if (value instanceof Object[] arguments) {
            return arguments;
        }
        return value instanceof TailCall tail ? tail.arguments : null;
    }

    /** Whether {@code value} requests a tail call ({@link #argumentsOf}). */
    public static boolean is(Object value) {
        return value instanceof Object[] || value instanceof TailCall;
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
