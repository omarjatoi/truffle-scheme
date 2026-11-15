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

    GlobalCell(SSymbol name) {
        this.name = name;
        this.stable = Truffle.getRuntime().createAssumption("global " + name);
    }

    public Assumption getStable() {
        return stable;
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
        stable.invalidate("assigned");
        value = newValue;
    }

    @TruffleBoundary
    private SchemeError unbound() {
        return new SchemeError("unbound variable: " + name);
    }
}
