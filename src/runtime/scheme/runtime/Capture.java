package scheme.runtime;

import java.util.ArrayList;
import java.util.List;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.bytecode.ContinuationRootNode;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.interop.TruffleObject;

/**
 * The marker {@code call/cc} returns while a continuation is being captured (design.md
 * "Continuations"). Every non-tail call site that sees it suspends its frame into a segment. Nothing
 * creates one until M2.
 */
public final class Capture implements TruffleObject {

    public record Segment(ContinuationRootNode root, MaterializedFrame frame) {
    }

    private final List<Segment> segments = new ArrayList<>();

    @TruffleBoundary
    public void addSegment(ContinuationRootNode root, MaterializedFrame frame) {
        segments.add(new Segment(root, frame));
    }

    @TruffleBoundary
    public List<Segment> segments() {
        return List.copyOf(segments);
    }
}
