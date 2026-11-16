package scheme.runtime;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;

import scheme.runtime.value.SSymbol;

/**
 * A global variable (design.md D7). Compiled code treats the value as a constant while
 * {@link #getStable()} holds; any redefinition or {@code set!} invalidates it for good, after which
 * reads go through the field.
 */
public final class GlobalCell {

    public final SSymbol name;
    /** null while unbound. */
    private Object value;
    private final Assumption stable;
    /** See {@link #isFixed()}. */
    private boolean fixed;

    GlobalCell(SSymbol name) {
        this.name = name;
        this.stable = Truffle.getRuntime().createAssumption("global " + name);
    }

    public Assumption getStable() {
        return stable;
    }

    /**
     * Whether the cell's value can never change: a primitive's own cell, in the primitives'
     * namespace (r7rs %primitives), bound when the context starts. Programs, libraries and the REPL
     * import it but can't assign it (a syntax error) or redefine it (a definition makes a new
     * variable, design.md D15), so the compiler may compile a call of it to an operation of its own
     * (plan.md M7-03, {@code Intrinsics}). Defining or assigning it is an internal error.
     */
    public boolean isFixed() {
        return fixed;
    }

    /** Makes the bound cell {@linkplain #isFixed() fixed}. */
    void fix() {
        assert value != null;
        fixed = true;
    }

    /** The value, or null if unbound. */
    public Object valueOrNull() {
        return value;
    }

    public Object read() {
        Object v = value;
        if (v == null) {
            throw unbound();
        }
        return v;
    }

    @TruffleBoundary
    public void define(Object newValue) {
        if (fixed) {
            throw fixedChanged();
        }
        if (value != null) {
            stable.invalidate("redefined");
        }
        value = newValue;
    }

    @TruffleBoundary
    public void set(Object newValue) {
        if (value == null) {
            throw unbound();
        }
        if (fixed) {
            throw fixedChanged();
        }
        stable.invalidate("assigned");
        value = newValue;
    }

    /** An internal error, never a Scheme one (design.md D8): the expander lets no code change it. */
    @TruffleBoundary
    private IllegalStateException fixedChanged() {
        return new IllegalStateException("the primitive " + name + " can't be redefined or assigned");
    }

    @TruffleBoundary
    private SchemeError unbound() {
        return new SchemeError("unbound variable: " + name);
    }
}
