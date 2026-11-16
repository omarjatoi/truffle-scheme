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
 * <p>A continuation's segments are shared: they are never resumed in place, every resume copies
 * the frame (design.md "Continuations", AGENTS.md rule 3), so a continuation can be invoked any
 * number of times. Making one ({@link #capture}) therefore makes every segment there is shared.
 */
@ExportLibrary(InteropLibrary.class)
public final class Continuation implements TruffleObject {

    public final SegmentList segments;
    public final DynEnv dynEnv;
    public final boolean composable;

    private Continuation(SegmentList segments, DynEnv dynEnv, boolean composable) {
        this.segments = segments;
        this.dynEnv = dynEnv;
        this.composable = composable;
    }

    /**
     * A continuation of {@code segments}: the only way to make one, so that no segment a driver
     * resumes in place is ever reachable from a continuation (design.md "Owned segments").
     */
    static Continuation capture(SegmentList segments, DynEnv dynEnv, boolean composable, SchemeContext context) {
        context.shareSegments();
        return new Continuation(segments, dynEnv, composable);
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
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return "#<continuation>";
    }
}
