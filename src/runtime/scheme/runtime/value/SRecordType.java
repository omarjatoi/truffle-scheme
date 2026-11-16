package scheme.runtime.value;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import scheme.runtime.InteropValues;
import scheme.runtime.SchemeLanguage;

/** A record type from {@code define-record-type}. */
@ExportLibrary(InteropLibrary.class)
public final class SRecordType implements TruffleObject {

    public final String name;
    public final String[] fieldNames;

    public SRecordType(String name, String[] fieldNames) {
        this.name = name;
        this.fieldNames = fieldNames;
    }

    // EXPLORATION (design.md D10): a Scheme value to interop, shown as write prints it.

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
