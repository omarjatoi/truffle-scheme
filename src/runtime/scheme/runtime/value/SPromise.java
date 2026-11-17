package scheme.runtime.value;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import scheme.runtime.InteropValues;
import scheme.runtime.SchemeLanguage;

/**
 * A promise (R7RS §4.2.5), what {@code delay}, {@code delay-force} and {@code make-promise} make: a
 * type of its own (design.md D1), so no other type's predicate holds for one. It is R7RS §7.3's
 * reference implementation's promise, a reference to a {@link State} that is done (it holds the
 * value) or not (it holds the thunk that computes the promise to take the value from). Forcing a
 * {@code delay-force} chain makes each promise of the chain share the outer promise's state
 * ({@code force} in lazy.scm, design.md D9), so iterative lazy algorithms run in constant space.
 */
@ExportLibrary(InteropLibrary.class)
public final class SPromise implements TruffleObject {

    /** The state a promise shares with the promises a forcing of it has linked to it. */
    public static final class State {
        public boolean done;
        /** The value if done, else a thunk that returns a promise. */
        public Object value;

        State(boolean done, Object value) {
            this.done = done;
            this.value = value;
        }
    }

    public State state;

    public SPromise(boolean done, Object value) {
        this.state = new State(done, value);
    }

    /**
     * §7.3's {@code promise-update!}: {@code outer} takes this promise's state (done or not), and
     * this promise shares {@code outer}'s state from now on.
     */
    public void updateInto(SPromise outer) {
        outer.state.done = state.done;
        outer.state.value = state.value;
        state = outer.state;
    }

    // Interop (design.md D10): a promise is opaque, a value with a meta object and a display string.

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
        return SchemeType.PROMISE;
    }

    @ExportMessage
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return InteropValues.display(this);
    }
}
