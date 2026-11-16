package scheme.runtime.value;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import scheme.runtime.InteropValues;
import scheme.runtime.SchemeLanguage;

/**
 * An error object, as created by {@code error} or by a primitive that fails (design.md D8).
 * {@code kind} is what {@code file-error?} and {@code read-error?} test. To embedders, its
 * {@code message} and {@code irritants} are read-only members (D10), what
 * {@code error-object-message} and {@code error-object-irritants} return.
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

    private static final String MESSAGE = "message";
    private static final String IRRITANTS = "irritants";

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
    boolean hasMembers() {
        return true;
    }

    @ExportMessage
    @TruffleBoundary
    Object getMembers(@SuppressWarnings("unused") boolean includeInternal) {
        return new SVector(new Object[]{SString.of(MESSAGE), SString.of(IRRITANTS)}, true);
    }

    @ExportMessage
    @TruffleBoundary
    boolean isMemberReadable(String member) {
        return MESSAGE.equals(member) || IRRITANTS.equals(member);
    }

    @ExportMessage
    @TruffleBoundary
    Object readMember(String member) throws UnknownIdentifierException {
        if (MESSAGE.equals(member)) {
            return message;
        }
        if (IRRITANTS.equals(member)) {
            return irritants;
        }
        throw UnknownIdentifierException.create(member);
    }

    @ExportMessage
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return InteropValues.display(this);
    }
}
