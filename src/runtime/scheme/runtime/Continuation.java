package scheme.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/**
 * A captured continuation: its segments and flattened prompts (innermost first) and the dynamic
 * environment at capture time. A full continuation (call/cc) reaches the program's root prompt and
 * replaces the current continuation when invoked; a composable one (delimited capture, D4) ends at a
 * prompt and is pushed onto the current continuation when invoked, returning to the caller.
 *
 * <p>Segments are never resumed in place: every resume copies the frame (design.md
 * "Continuations"), so a continuation can be invoked any number of times.
 */
@ExportLibrary(InteropLibrary.class)
public final class Continuation implements TruffleObject {

    public final SegmentList segments;
    public final DynEnv dynEnv;
    public final boolean composable;
    /**
     * EXPLORATION: for a full continuation, the root prompt run that captured it
     * ({@link SchemeContext#rootRun}). A root prompt that foreign frames separate from its caller (an
     * interop call) only resumes its own continuations; any other passes through it, unwinding the
     * foreign frames (an escape).
     */
    public Object owner;

    public Continuation(SegmentList segments, DynEnv dynEnv, boolean composable) {
        this.segments = segments;
        this.dynEnv = dynEnv;
        this.composable = composable;
    }

    // EXPLORATION (design.md D10): a Scheme value to interop.

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
        return "#<continuation>";
    }
}
