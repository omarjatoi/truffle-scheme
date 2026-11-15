package scheme.spike;

import java.util.ArrayList;
import java.util.List;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.bytecode.ContinuationRootNode;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.interop.TruffleObject;

/** The marker that {@code call/cc} returns. Each suspended frame appends its segment, innermost first. */
final class Capture implements TruffleObject {

    /** The {@code mode} argument that tells {@code CallCC} to capture rather than return the mode. */
    static final Object REQUEST = new Object() {
        @Override
        public String toString() {
            return "CAPTURE";
        }
    };

    record Segment(ContinuationRootNode root, MaterializedFrame frame) {
    }

    private final List<Segment> segments = new ArrayList<>();

    @TruffleBoundary
    static Capture create() {
        return new Capture();
    }

    @TruffleBoundary
    void addSegment(ContinuationRootNode root, MaterializedFrame frame) {
        segments.add(new Segment(root, frame));
    }

    @TruffleBoundary
    List<Segment> segments() {
        return List.copyOf(segments);
    }
}
