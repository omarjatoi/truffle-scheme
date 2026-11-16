package scheme.runtime.value;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
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

    // To interop (design.md D10): the empty list and the unspecified value are null, and the empty
    // list is also an (empty) iterable, like every other list.

    @ExportMessage
    boolean isNull() {
        return this == EMPTY_LIST || this == UNSPECIFIED;
    }

    @ExportMessage
    boolean hasIterator() {
        return this == EMPTY_LIST;
    }

    @ExportMessage
    Object getIterator() throws UnsupportedMessageException {
        if (this != EMPTY_LIST) {
            throw UnsupportedMessageException.create();
        }
        return new SListIterator(this);
    }

    /** Its meta object (design.md D10). */
    SchemeType metaObject() {
        if (this == EMPTY_LIST) {
            return SchemeType.NULL;
        } else if (this == UNSPECIFIED) {
            return SchemeType.UNSPECIFIED;
        } else if (this == EOF) {
            return SchemeType.EOF_OBJECT;
        }
        return SchemeType.DEFAULT_OBJECT;
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
        return metaObject();
    }

    @ExportMessage
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return InteropValues.display(this);
    }
}
