package scheme.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import scheme.runtime.value.SConstant;

/**
 * The dynamic environment (design.md "Dynamic environment"): one immutable record per state, held in
 * a per-context slot ({@link SchemeContext#getDynamicEnv()}). Every continuation captures the
 * current one (M2-02); every change of control goes through {@code rewind} (M2-04).
 */
@ExportLibrary(InteropLibrary.class)
public final class DynEnv implements TruffleObject {

    public static final DynEnv INITIAL = new DynEnv(SConstant.EMPTY_LIST, SConstant.EMPTY_LIST, SConstant.EMPTY_LIST);

    /** A Scheme list: the dynamic-wind winders in effect, innermost first. */
    public final Object winders;
    /** A Scheme list: parameter bindings (parameterize). */
    public final Object parameterization;
    /** A Scheme list: exception handlers (with-exception-handler), innermost first. */
    public final Object handlers;

    public DynEnv(Object winders, Object parameterization, Object handlers) {
        this.winders = winders;
        this.parameterization = parameterization;
        this.handlers = handlers;
    }

    // Interop (design.md D10).

    @ExportMessage
    boolean hasLanguageId() {
        return true;
    }

    @ExportMessage
    String getLanguageId() {
        return SchemeLanguage.ID;
    }

    @ExportMessage
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return InteropValues.display(this);
    }
}
