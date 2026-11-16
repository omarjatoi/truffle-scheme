package scheme.runtime.value;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.StopIterationException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/**
 * EXPLORATION (design.md D10): an interop iterator over a list's elements ({@link SPair}), up to the
 * first non-pair. Not a Scheme value: only interop sees it.
 */
@ExportLibrary(InteropLibrary.class)
final class SListIterator implements TruffleObject {

    private Object rest;

    SListIterator(Object list) {
        this.rest = list;
    }

    @ExportMessage
    boolean isIterator() {
        return true;
    }

    @ExportMessage
    boolean hasIteratorNextElement() {
        return rest instanceof SPair;
    }

    @ExportMessage
    Object getIteratorNextElement() throws StopIterationException {
        if (!(rest instanceof SPair pair)) {
            throw StopIterationException.create();
        }
        rest = pair.cdr;
        return pair.car;
    }
}
