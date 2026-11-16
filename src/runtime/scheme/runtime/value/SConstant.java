package scheme.runtime.value;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import scheme.runtime.InteropValues;
import scheme.runtime.SchemeLanguage;

/** The singleton values: the empty list, the unspecified value, the eof object and the default object. */
@ExportLibrary(InteropLibrary.class)
public final class SConstant implements TruffleObject {

    public static final SConstant EMPTY_LIST = new SConstant("()");
    public static final SConstant UNSPECIFIED = new SConstant("#<undef>");
    public static final SConstant EOF = new SConstant("#<eof>");
    public static final SConstant DEFAULT = new SConstant("#<default>");

    private final String printName;

    private SConstant(String printName) {
        this.printName = printName;
    }

    @Override
    public String toString() {
        return printName;
    }

    // EXPLORATION (design.md D10): the empty list and the unspecified value are null to interop.

    @ExportMessage
    boolean isNull() {
        return this == EMPTY_LIST || this == UNSPECIFIED;
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
