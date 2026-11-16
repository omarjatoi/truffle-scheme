package scheme.runtime.value;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import scheme.runtime.InteropValues;
import scheme.runtime.SchemeLanguage;

/**
 * An environment specifier (R7RS §6.12, design.md D7): what {@code environment},
 * {@code interaction-environment}, {@code scheme-report-environment} and {@code null-environment}
 * return, and {@code eval} takes. {@link #state} is the compiler's (it sits above the runtime):
 * the environment's top-level scope and namespace.
 */
@ExportLibrary(InteropLibrary.class)
public final class SEnvironment implements TruffleObject {

    public final Object state;

    public SEnvironment(Object state) {
        this.state = state;
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
    boolean hasMetaObject() {
        return true;
    }

    @ExportMessage
    Object getMetaObject() {
        return SchemeType.ENVIRONMENT;
    }

    @ExportMessage
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return InteropValues.display(this);
    }
}
