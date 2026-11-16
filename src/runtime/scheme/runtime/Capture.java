package scheme.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.bytecode.ContinuationRootNode;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

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
 * array with the procedure at index 0) on a fresh Java stack. Only the root prompt's driver can
 * reach the frames that suspended, so it resumes them in place until something shares them
 * ({@link Owner}, design.md "Owned segments").</li>
 * <li>{@link Kind#TORTURE}: torture mode (M2-06). A call returned {@link #value} normally; capture
 * up to the root prompt anyway, which resumes every frame from a copy with that value.</li>
 * </ul>
 *
 * <p>A procedure whose frame suspends returns it, so tools may see it as a return value; they show
 * it as {@code #<capture>}, not as a foreign object (plan.md M6-05).
 */
@ExportLibrary(InteropLibrary.class)
public final class Capture implements TruffleObject {

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
        return "#<capture>";
    }

    public enum Kind {
        FULL,
        DELIMITED,
        REINSTATE,
        SPILL,
        TORTURE
    }

    /**
     * One suspended frame: resumed by calling {@code root} with {@code frame}, or with a copy of it
     * once it is shared (design.md "Owned segments", AGENTS.md rule 3). {@code owner} is the token
     * of the spill that suspended it, or null: a segment of any other capture is shared from the
     * start.
     */
    public record Segment(ContinuationRootNode root, MaterializedFrame frame, Owner owner) {

        /**
         * Whether only the prompt driver that holds it can reach this segment, so that it may resume
         * the original frame, once: its spill's token was stamped with the context's sharing epoch,
         * and nothing has bumped the epoch since ({@link SchemeContext#shareSegments}).
         */
        boolean isOwned(SchemeContext context) {
            return owner != null && owner.epoch == context.sharingEpoch;
        }

        /** Makes this segment, and every other of its spill, shared for good. */
        void share() {
            if (owner != null) {
                owner.epoch = Owner.SHARED;
            }
        }
    }

    /**
     * The token the segments of one stack-depth spill share. The root prompt's driver stamps it with
     * the context's sharing epoch when it takes them ({@link #claim}); the segments are owned while
     * that is the current epoch. Unstamped, or stamped {@link #SHARED}, they are shared.
     */
    static final class Owner {
        static final long SHARED = -1;

        long epoch = SHARED;
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
    /**
     * Segments and prompt markers, innermost first: the cells of the list {@link #segmentsBefore}
     * hands over, built front to back as the frames suspend, outward (plan.md M8-01: no array to
     * grow and copy, and no second list at the end). Null while empty.
     */
    private SegmentList first;
    private SegmentList last;
    /** Whether {@link #segmentsBefore} has handed the list over: nothing may be added after. */
    private boolean handedOver;
    /** For SPILL: the token of the segments its frames suspend into ({@link Segment#owner}). */
    private final Owner owner;

    private Capture(Kind kind, Object tag, Object receiver, DynEnv dynEnv, Continuation continuation, Object value) {
        this.kind = kind;
        this.tag = tag;
        this.receiver = receiver;
        this.dynEnv = dynEnv;
        this.continuation = continuation;
        this.value = value;
        this.owner = kind == Kind.SPILL ? new Owner() : null;
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

    /** A frame suspends into the capture: it goes after (outside) the frames already in it. */
    public void addSegment(ContinuationRootNode root, MaterializedFrame frame) {
        append(new Segment(root, frame, owner));
    }

    private void append(Object item) {
        if (handedOver) {
            throw handedOver();
        }
        SegmentList cell = new SegmentList(item, null);
        if (last == null) {
            first = cell;
        } else {
            last.tail = cell;
        }
        last = cell;
    }

    @TruffleBoundary
    private static IllegalStateException handedOver() {
        return new IllegalStateException("a capture's segments were added to after it was completed");
    }

    /** Whether a prompt with {@code tag} was flattened into the capture (before it is handed over). */
    boolean hasPrompt(Object promptTag) {
        return SegmentList.hasPrompt(first, promptTag);
    }

    /**
     * A prompt driver passes the capture through: its pending segments, then itself as a marker.
     * The segments it re-links are made shared (design.md "Owned segments"): only a root prompt's
     * driver resumes a segment in place, and only one whose spill it claimed itself.
     */
    @TruffleBoundary
    void addOuter(SegmentList pending, PromptMarker prompt) {
        for (SegmentList l = pending; l != null; l = l.tail) {
            if (l.head instanceof Segment segment) {
                segment.share();
            }
            append(l.head);
        }
        append(prompt);
    }

    /**
     * A SPILL capture is complete at the root prompt, whose driver now alone holds its segments:
     * they are owned until the epoch changes (design.md "Owned segments").
     */
    void claim(SchemeContext context) {
        owner.epoch = context.sharingEpoch;
    }

    /**
     * This capture's segments, innermost first, followed by {@code outer}: the list its frames
     * suspended into, now linked to {@code outer}, and so handed over once, by the driver that
     * completes the capture.
     */
    public SegmentList segmentsBefore(SegmentList outer) {
        if (handedOver) {
            throw handedOver();
        }
        handedOver = true;
        if (last == null) {
            return outer;
        }
        last.tail = outer;
        return first;
    }

    /** eq? on tags: identity, fixnums and characters by value (design.md D1). */
    static boolean sameTag(Object a, Object b) {
        return a == b || (a instanceof Long x && b instanceof Long y && x.longValue() == y.longValue());
    }
}
