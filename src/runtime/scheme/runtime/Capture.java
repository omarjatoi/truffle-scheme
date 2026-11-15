package scheme.runtime;

import java.util.ArrayList;
import java.util.List;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.bytecode.ContinuationRootNode;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.interop.TruffleObject;

/**
 * The marker {@code call/cc} returns while a continuation is being captured (design.md
 * "Continuations"). Every non-tail call site that sees it suspends its frame into a segment
 * ({@code SuspendFrame}) and passes it up, until the prompt completes it into a {@link Continuation}
 * and calls the receiver with it.
 */
public final class Capture implements TruffleObject {

    /** One suspended frame: resumed by calling {@code root} with a copy of {@code frame}. */
    public record Segment(ContinuationRootNode root, MaterializedFrame frame) {
    }

    /** The procedure call/cc was given. */
    public final Object receiver;
    /** The dynamic environment when call/cc was called. */
    public final DynEnv dynEnv;
    /** Innermost first. */
    private final List<Segment> segments = new ArrayList<>();

    public Capture(Object receiver, DynEnv dynEnv) {
        this.receiver = receiver;
        this.dynEnv = dynEnv;
    }

    @TruffleBoundary
    public void addSegment(ContinuationRootNode root, MaterializedFrame frame) {
        segments.add(new Segment(root, frame));
    }

    /** This capture's segments, innermost first, followed by {@code outer}. */
    @TruffleBoundary
    public SegmentList segmentsBefore(SegmentList outer) {
        SegmentList list = outer;
        for (int i = segments.size() - 1; i >= 0; i--) {
            list = new SegmentList(segments.get(i), list);
        }
        return list;
    }
}
