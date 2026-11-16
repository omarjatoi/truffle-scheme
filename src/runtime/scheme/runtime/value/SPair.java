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

    // EXPLORATION (design.md D10): a pair is iterable to interop: its list's elements, up to the first
    // non-pair (so an improper list's last cdr is left out). JavaScript's for-of and the polyglot
    // API's Value.getIterator() walk it lazily, so a long list is never copied.

    @ExportMessage
    boolean hasIterator() {
        return true;
    }

    @ExportMessage
    Object getIterator() {
        return new SListIterator(this);
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
