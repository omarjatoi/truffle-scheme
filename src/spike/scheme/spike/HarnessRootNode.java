package scheme.spike;

import java.util.List;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * Plays the program's root prompt. Runs f1, and when a {@link Capture} comes back, resumes its
 * segments innermost first (design.md "Invoking a continuation"), once per resume value.
 *
 * <ul>
 * <li>{@code direct v}: no capture; callcc returns v. A fresh run, for expected values.</li>
 * <li>{@code multishot v...}: capture once, then resume with each v, copying every frame first.</li>
 * <li>{@code control v...}: the same, but resuming the original frames (no copy).</li>
 * </ul>
 */
final class HarnessRootNode extends RootNode {

    private final SpikePrograms programs;
    private final String mode;
    private final long[] values;
    @Child private IndirectCallNode callNode = IndirectCallNode.create();

    HarnessRootNode(SpikeLanguage language, SpikePrograms programs, String mode, long[] values) {
        super(language);
        this.programs = programs;
        this.mode = mode;
        this.values = values;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return run();
    }

    @TruffleBoundary
    private Object run() {
        Box box = new Box();
        if (mode.equals("direct")) {
            return callNode.call(programs.f1, box, values[0]);
        }
        boolean copy = switch (mode) {
            case "multishot" -> true;
            case "control" -> false;
            default -> throw new IllegalArgumentException("unknown spike mode: " + mode);
        };
        Capture capture = (Capture) callNode.call(programs.f1, box, Capture.REQUEST);
        List<Capture.Segment> segments = capture.segments();
        StringBuilder out = new StringBuilder();
        out.append("segments=").append(segments.size());
        for (long value : values) {
            Object result = value;
            try {
                for (Capture.Segment segment : segments) {
                    MaterializedFrame resumeFrame = copy ? copyFrame(segment.frame()) : segment.frame();
                    result = callNode.call(segment.root().getCallTarget(), resumeFrame, result);
                }
            } catch (RuntimeException e) {
                result = "error:" + e.getClass().getSimpleName();
            }
            out.append(' ').append(result);
        }
        out.append(" box=").append(box.value);
        return out.toString();
    }

    /**
     * The same copy {@code BytecodeFrame.copy()} makes internally (arguments plus every slot via
     * {@code Frame.copyTo}). {@code BytecodeFrame} has no accessor for the underlying frame, so its
     * copy can't be passed to a ContinuationRootNode, which takes a MaterializedFrame.
     */
    static MaterializedFrame copyFrame(MaterializedFrame frame) {
        FrameDescriptor descriptor = frame.getFrameDescriptor();
        MaterializedFrame copy = Truffle.getRuntime().createMaterializedFrame(frame.getArguments().clone(), descriptor);
        frame.copyTo(0, copy, 0, descriptor.getNumberOfSlots());
        return copy;
    }
}
