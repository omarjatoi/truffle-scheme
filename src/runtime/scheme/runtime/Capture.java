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
 * <li>{@link Kind#APPLY}: invoking a full continuation ({@code %apply-continuation}). Abandons the
 * current continuation up to the root prompt, which resumes the continuation's segments instead,
 * with {@link #value}. The frames it passes are abandoned, so it keeps none of them: it travels up
 * as a returned value, not as a Java exception, whose handlers the host compiler may have left out
 * of the compiled interpreter (design.md "Continuations").</li>
 * <li>{@link Kind#ESCAPE}: invoking an escape-only continuation (M5-04, {@link EscapeRootNode}).
 * Abandons the current continuation up to the {@code %call/ec} prompt whose tag is {@link #tag},
 * which returns {@link #value}; a prompt driver that holds that prompt flattened into a marker
 * drops its pending segments up to it instead. Like APPLY, it keeps none of the frames it passes,
 * and travels up as a returned value (M9-04).</li>
 * <li>{@link Kind#SPILL}: stack-depth spill. A call site past the depth threshold captures up to
 * the root prompt instead of calling; the root prompt makes the call ({@link #value}, an argument
 * array with the procedure at index 0) on a fresh Java stack. Only the root prompt's driver can
 * reach the frames that suspended, so it resumes them in place until something shares them
 * ({@link Owner}, design.md "Owned segments").</li>
 * <li>{@link Kind#TORTURE}: torture mode (M2-06). A call returned {@link #value} normally; capture
 * up to the root prompt anyway, which resumes every frame from a copy with that value. Under owned
 * torture ({@link SchemeRootNode#TORTURE_OWNED}) the root prompt claims the frames as it claims a
 * spill's, and resumes them in place until something shares them.</li>
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
        APPLY,
        ESCAPE,
        SPILL,
        TORTURE
    }

    /**
     * One suspended frame: resumed by calling {@code root} with {@code frame}, or with a copy of it
     * once it is shared (design.md "Owned segments", AGENTS.md rule 3). {@code owner} is the token
     * of the spill (or owned torture capture, {@link SchemeRootNode#TORTURE_OWNED}) that suspended
     * it, or null: a segment of any other capture is shared from the start.
     */
    public static final class Segment {
        private final ContinuationRootNode root;
        private final MaterializedFrame frame;
        private final Owner owner;
        /** Torture mode: whether a driver has resumed the original frame: then never again (rule 3). */
        private boolean resumedInPlace;

        Segment(ContinuationRootNode root, MaterializedFrame frame, Owner owner) {
            this.root = root;
            this.frame = frame;
            this.owner = owner;
        }

        public ContinuationRootNode root() {
            return root;
        }

        public MaterializedFrame frame() {
            return frame;
        }

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

        /**
         * Torture mode (AGENTS.md rule 3): the driver is about to resume this segment, from its
         * original frame if {@code inPlace}, else from a copy. Once the original has been resumed,
         * the segment must never be resumed again, either way: that would mean that something else
         * (a continuation) could reach a segment the driver took for owned.
         */
        void checkResume(boolean inPlace) {
            if (resumedInPlace) {
                throw resumedAgain(inPlace);
            }
            resumedInPlace = inPlace;
        }

        @TruffleBoundary
        private static IllegalStateException resumedAgain(boolean inPlace) {
            // An internal error, never a Scheme error (design.md D8).
            return new IllegalStateException("torture: a continuation segment resumed in place was resumed again, " + (inPlace ? "in place" : "from a copy") +
                            " (AGENTS.md rule 3)");
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
    /** For REINSTATE and APPLY. */
    public final Continuation continuation;
    /** The value to go on with (REINSTATE, APPLY, ESCAPE, TORTURE), or the spilled call's arguments (SPILL). */
    private Object value;
    /**
     * Segments and prompt markers, innermost first: the cells of the list {@link #segmentsBefore}
     * hands over, built front to back as the frames suspend, outward (plan.md M8-01: no array to
     * grow and copy, and no second list at the end). Null while empty.
     */
    private SegmentList first;
    private SegmentList last;
    /** Whether {@link #segmentsBefore} has handed the list over: nothing may be added after. */
    private boolean handedOver;
    /**
     * For SPILL, and TORTURE under owned torture: the token of the segments its frames suspend into
     * ({@link Segment#isOwned}).
     */
    private final Owner owner;

    private Capture(Kind kind, Object tag, Object receiver, DynEnv dynEnv, Continuation continuation, Object value) {
        this.kind = kind;
        this.tag = tag;
        this.receiver = receiver;
        this.dynEnv = dynEnv;
        this.continuation = continuation;
        this.value = value;
        this.owner = kind == Kind.SPILL || (kind == Kind.TORTURE && SchemeRootNode.TORTURE_OWNED) ? new Owner() : null;
    }

    /**
     * Takes {@link #value}, for the driver that handles the capture: it is the capture's alone,
     * and the capture may stay reachable (from a driver's or a prompt's Java frame) while the
     * program goes on with it (design.md "Tail calls keep nothing alive").
     */
    Object takeValue() {
        Object v = value;
        value = null;
        return v;
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

    public static Capture apply(Continuation continuation, Object value, DynEnv dynEnv) {
        return new Capture(Kind.APPLY, ROOT_TAG, null, dynEnv, continuation, value);
    }

    /** k of the {@code %call/ec} prompt with {@code target} invoked with {@code value}. */
    public static Capture escape(EscapeRootNode.Target target, Object value, DynEnv dynEnv) {
        return new Capture(Kind.ESCAPE, target, null, dynEnv, null, value);
    }

    public static Capture torture(Object value, DynEnv dynEnv) {
        return new Capture(Kind.TORTURE, ROOT_TAG, null, dynEnv, null, value);
    }

    public static Capture spill(Object[] arguments, DynEnv dynEnv) {
        return new Capture(Kind.SPILL, ROOT_TAG, null, dynEnv, null, arguments);
    }

    /**
     * A frame suspends into the capture: it goes after (outside) the frames already in it. An APPLY
     * or ESCAPE capture abandons the frame instead: nothing resumes it.
     */
    public void addSegment(ContinuationRootNode root, MaterializedFrame frame) {
        if (abandons()) {
            return;
        }
        append(new Segment(root, frame, owner));
    }

    /** Whether the frames and prompts this capture passes are abandoned: APPLY and ESCAPE. */
    private boolean abandons() {
        return kind == Kind.APPLY || kind == Kind.ESCAPE;
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
        if (abandons()) {
            // Abandoned with the rest of the current continuation.
            return;
        }
        for (SegmentList l = pending; l != null; l = l.tail) {
            if (l.head instanceof Segment segment) {
                segment.share();
            }
            append(l.head);
        }
        append(prompt);
    }

    /**
     * A SPILL capture (or an owned torture one) is complete at the root prompt, whose driver now
     * alone holds its segments: they are owned until the epoch changes (design.md "Owned segments").
     */
    void claim(SchemeContext context) {
        owner.epoch = context.sharingEpoch;
    }

    /**
     * This capture's segments, innermost first, followed by {@code outer}: the list its frames
     * suspended into, now linked to {@code outer}, and so handed over once, by the driver that
     * completes the capture. The capture lets go of it: a suspended frame's stale operand slot may
     * keep the capture while the program goes on without those frames (design.md "Tail calls keep
     * nothing alive").
     */
    public SegmentList segmentsBefore(SegmentList outer) {
        if (handedOver) {
            throw handedOver();
        }
        handedOver = true;
        SegmentList list = first;
        if (last == null) {
            return outer;
        }
        last.tail = outer;
        first = null;
        last = null;
        return list;
    }

    /** eq? on tags: identity, fixnums and characters by value (design.md D1). */
    static boolean sameTag(Object a, Object b) {
        return a == b || (a instanceof Long x && b instanceof Long y && x.longValue() == y.longValue());
    }
}
