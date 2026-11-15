package scheme.runtime;

import com.oracle.truffle.api.interop.TruffleObject;

/**
 * A captured continuation: its segments and flattened prompts (innermost first) and the dynamic
 * environment at capture time. A full continuation (call/cc) reaches the program's root prompt and
 * replaces the current continuation when invoked; a composable one (delimited capture, D4) ends at a
 * prompt and is pushed onto the current continuation when invoked, returning to the caller.
 *
 * <p>Segments are never resumed in place: every resume copies the frame (design.md
 * "Continuations"), so a continuation can be invoked any number of times.
 */
public final class Continuation implements TruffleObject {

    public final SegmentList segments;
    public final DynEnv dynEnv;
    public final boolean composable;

    public Continuation(SegmentList segments, DynEnv dynEnv, boolean composable) {
        this.segments = segments;
        this.dynEnv = dynEnv;
        this.composable = composable;
    }
}
