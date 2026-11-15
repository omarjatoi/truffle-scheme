package scheme.runtime;

import com.oracle.truffle.api.interop.TruffleObject;

/**
 * A captured continuation: its segments (innermost first, up to the prompt) and the dynamic
 * environment at capture time. Segments are never resumed in place: every resume copies the frame
 * (design.md "Continuations"), so a continuation can be invoked any number of times.
 */
public final class Continuation implements TruffleObject {

    public final SegmentList segments;
    public final DynEnv dynEnv;

    public Continuation(SegmentList segments, DynEnv dynEnv) {
        this.segments = segments;
        this.dynEnv = dynEnv;
    }
}
