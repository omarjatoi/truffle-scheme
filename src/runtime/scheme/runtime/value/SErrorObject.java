package scheme.runtime.value;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import scheme.runtime.InteropValues;
import scheme.runtime.SchemeLanguage;

/**
 * An error object, as created by {@code error} or by a primitive that fails (design.md D8).
 * {@code kind} is what {@code file-error?} and {@code read-error?} test.
 */
@ExportLibrary(InteropLibrary.class)
public final class SErrorObject implements TruffleObject {

    public enum Kind {
        FILE,
        READ,
        OTHER
    }

    /** A Scheme string. */
    public final Object message;
    /** A Scheme list. */
    public final Object irritants;
    public final Kind kind;

    public SErrorObject(Object message, Object irritants, Kind kind) {
        this.message = message;
        this.irritants = irritants;
        this.kind = kind;
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
        return SchemeType.ERROR_OBJECT;
    }

    @ExportMessage
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return InteropValues.display(this);
    }
}
