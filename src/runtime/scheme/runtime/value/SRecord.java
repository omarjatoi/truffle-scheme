package scheme.runtime.value;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import scheme.runtime.InteropValues;
import scheme.runtime.SchemeLanguage;

/** An instance of an {@link SRecordType}. */
@ExportLibrary(InteropLibrary.class)
public final class SRecord implements TruffleObject {

    public final SRecordType type;
    public final Object[] fields;

    public SRecord(SRecordType type, Object[] fields) {
        this.type = type;
        this.fields = fields;
    }

    // EXPLORATION (design.md D10): a record is an object to interop, its fields read-only members.

    @ExportMessage
    boolean hasMembers() {
        return true;
    }

    @ExportMessage
    @TruffleBoundary
    Object getMembers(@SuppressWarnings("unused") boolean includeInternal) {
        Object[] names = new Object[type.fieldNames.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = SString.of(type.fieldNames[i]);
        }
        return new SVector(names, true);
    }

    @ExportMessage
    @TruffleBoundary
    boolean isMemberReadable(String member) {
        return fieldIndex(member) >= 0;
    }

    @ExportMessage
    @TruffleBoundary
    Object readMember(String member) throws UnknownIdentifierException {
        int i = fieldIndex(member);
        if (i < 0) {
            throw UnknownIdentifierException.create(member);
        }
        return fields[i];
    }

    private int fieldIndex(String member) {
        for (int i = 0; i < type.fieldNames.length; i++) {
            if (type.fieldNames[i].equals(member)) {
                return i;
            }
        }
        return -1;
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
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return InteropValues.display(this);
    }
}
