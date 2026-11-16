package scheme.runtime.value;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import scheme.runtime.InteropValues;
import scheme.runtime.SchemeLanguage;

/** A mutable pair. */
@ExportLibrary(InteropLibrary.class)
public final class SPair implements TruffleObject {

    public Object car;
    public Object cdr;

    public SPair(Object car, Object cdr) {
        this.car = car;
        this.cdr = cdr;
    }

    // A list to interop (design.md D10): iterable, its elements up to the first non-pair (so an
    // improper list's last cdr is left out). The iterator walks the list lazily, never copying it.

    @ExportMessage
    boolean hasIterator() {
        return true;
    }

    @ExportMessage
    Object getIterator() {
        return new SListIterator(this);
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
        return SchemeType.PAIR;
    }

    @ExportMessage
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return InteropValues.display(this);
    }
}
