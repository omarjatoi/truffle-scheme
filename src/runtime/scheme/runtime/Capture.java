package scheme.runtime;

import java.util.ArrayList;
import java.util.List;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.bytecode.ContinuationRootNode;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.interop.TruffleObject;

/**
 * A control request travelling up the stack (design.md "Continuations", D4). Every non-tail call
 * site that sees one suspends its frame into it ({@code SuspendFrame}) and passes it up, until a
 * prompt driver ({@link PromptDriver}) handles it.
 *
 * <ul>
 * <li>{@link Kind#FULL}: {@code call/cc}. Captures up to the program's root prompt; the receiver is
 * called inside the captured continuation.</li>
 * <li>{@link Kind#DELIMITED}: {@code %capture-to-prompt}. Captures up to the nearest prompt with
 * {@link #tag}, aborts to it, and calls the receiver there with the (composable) continuation.</li>
 * <li>{@link Kind#REINSTATE}: invoking a composable continuation. Captures up to the nearest prompt
 * of any tag, then pushes the continuation's segments on top and resumes them with {@link #value}.</li>
 * <li>{@link Kind#SPILL}: stack-depth spill. A call site past the depth threshold captures up to
 * the root prompt instead of calling; the root prompt makes the call ({@link #value}, an argument
 * array with the procedure at index 0) on a fresh Java stack.</li>
 * <li>{@link Kind#TORTURE}: torture mode (M2-06). A call returned {@link #value} normally; capture
 * up to the root prompt anyway, which resumes every frame from a copy with that value.</li>
 * </ul>
 */
public final class Capture implements TruffleObject {

    public enum Kind {
        FULL,
        DELIMITED,
        REINSTATE,
        SPILL,
        TORTURE
    }

    /** One suspended frame: resumed by calling {@code root} with a copy of {@code frame}. */
    public record Segment(ContinuationRootNode root, MaterializedFrame frame) {
    }

    /** A prompt that a capture crossed, flattened into a segment list: {@code tag} and its dynamic environment. */
    public record PromptMarker(Object tag, DynEnv dynEnv) {
    }

    /** The tag of the program's prompt (design.md D6). */
    public static final Object ROOT_TAG = new Object() {
        @Override
        public String toString() {
            return "root";
        }
    };

    public final Kind kind;
    public final Object tag;
    public final Object receiver;
    /** The dynamic environment when the capture started. */
    public final DynEnv dynEnv;
    /** For REINSTATE. */
    public final Continuation continuation;
    public final Object value;
    /** Segments and prompt markers, innermost first. */
    private final List<Object> segments = new ArrayList<>();

    private Capture(Kind kind, Object tag, Object receiver, DynEnv dynEnv, Continuation continuation, Object value) {
        this.kind = kind;
        this.tag = tag;
        this.receiver = receiver;
        this.dynEnv = dynEnv;
        this.continuation = continuation;
        this.value = value;
    }

    public static Capture full(Object receiver, DynEnv dynEnv) {
        return new Capture(Kind.FULL, ROOT_TAG, receiver, dynEnv, null, null);
    }

    public static Capture delimited(Object tag, Object receiver, DynEnv dynEnv) {
        return new Capture(Kind.DELIMITED, tag, receiver, dynEnv, null, null);
    }

    public static Capture reinstate(Continuation continuation, Object value, DynEnv dynEnv) {
        return new Capture(Kind.REINSTATE, null, null, dynEnv, continuation, value);
    }

    public static Capture torture(Object value, DynEnv dynEnv) {
        return new Capture(Kind.TORTURE, ROOT_TAG, null, dynEnv, null, value);
    }

    public static Capture spill(Object[] arguments, DynEnv dynEnv) {
        return new Capture(Kind.SPILL, ROOT_TAG, null, dynEnv, null, arguments);
    }

    @TruffleBoundary
    public void addSegment(ContinuationRootNode root, MaterializedFrame frame) {
        segments.add(new Segment(root, frame));
    }

    /** A prompt driver passes the capture through: its pending segments, then itself as a marker. */
    @TruffleBoundary
    void addOuter(SegmentList pending, PromptMarker prompt) {
        for (SegmentList l = pending; l != null; l = l.tail) {
            segments.add(l.head);
        }
        segments.add(prompt);
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

    /** eq? on tags: identity, fixnums and characters by value (design.md D1). */
    static boolean sameTag(Object a, Object b) {
        return a == b || (a instanceof Long x && b instanceof Long y && x.longValue() == y.longValue());
    }
}
