package scheme.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
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
    /** How many foreign calls were on the stack when it was captured (design.md D20). */
    private final int foreignDepth;

    private Continuation(SegmentList segments, DynEnv dynEnv, boolean composable, int foreignDepth) {
        this.segments = segments;
        this.dynEnv = dynEnv;
        this.composable = composable;
        this.foreignDepth = foreignDepth;
    }

    /**
     * Raises the error unless this continuation can be invoked here (design.md D20, "Continuations
     * across foreign frames"): with another number of foreign calls on the stack than when it was
     * captured, it would escape through foreign frames, or resume a callback whose foreign call has
     * returned. Checked before the rewind, so no {@code dynamic-wind} thunk runs first.
     */
    public void checkInvocable(SchemeContext context) {
        if (foreignDepth != context.foreignDepth) {
            throw crossesForeignFrame(foreignDepth < context.foreignDepth);
        }
    }

    @TruffleBoundary
    private static SchemeError crossesForeignFrame(boolean outward) {
        return new SchemeError(outward ? "continuation would escape through a foreign frame: it was captured outside the foreign call that this code runs in"
                        : "continuation would resume a foreign frame that has returned: it was captured in a callback from foreign code");
    }

    /**
     * A continuation of {@code segments}: the only way to make one, so that no segment a driver
     * resumes in place is ever reachable from a continuation (design.md "Owned segments").
     */
    static Continuation capture(SegmentList segments, DynEnv dynEnv, boolean composable, SchemeContext context) {
        context.shareSegments();
        return new Continuation(segments, dynEnv, composable, context.foreignDepth);
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
