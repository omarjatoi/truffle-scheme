package scheme.runtime;

/**
 * Returned from a tail call site instead of calling (design.md "Calls and tail calls"). The
 * trampoline at the nearest non-tail call site performs it. {@code arguments[0]} is the procedure.
 */
public final class TailCall implements com.oracle.truffle.api.interop.TruffleObject {

    public final Object procedure;
    public final Object[] arguments;

    public TailCall(Object procedure, Object[] arguments) {
        this.procedure = procedure;
        this.arguments = arguments;
    }
}
