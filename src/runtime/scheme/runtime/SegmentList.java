package scheme.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * An immutable list of continuation segments ({@link Capture.Segment}) and flattened prompts
 * ({@link Capture.PromptMarker}), innermost first. Continuations share tails, so captures share the
 * segments already on the heap (design.md "Continuations"). null is the empty list.
 */
public final class SegmentList {

    public final Object head;
    public final SegmentList tail;

    public SegmentList(Object head, SegmentList tail) {
        this.head = head;
        this.tail = tail;
    }

    /** {@code front} followed by {@code back}. Copies {@code front}'s cells. */
    @TruffleBoundary
    static SegmentList concat(SegmentList front, SegmentList back) {
        if (front == null) {
            return back;
        }
        java.util.ArrayList<Object> items = new java.util.ArrayList<>();
        for (SegmentList l = front; l != null; l = l.tail) {
            items.add(l.head);
        }
        SegmentList result = back;
        for (int i = items.size() - 1; i >= 0; i--) {
            result = new SegmentList(items.get(i), result);
        }
        return result;
    }

    /** The cell of the nearest prompt marker whose tag is {@code tag} (identity), or null if there is none. */
    @TruffleBoundary
    static SegmentList markerOf(SegmentList list, Object tag) {
        for (SegmentList l = list; l != null; l = l.tail) {
            if (l.head instanceof Capture.PromptMarker marker && marker.tag() == tag) {
                return l;
            }
        }
        return null;
    }

    /** The result of splitting a list at the nearest prompt marker with a tag. */
    record Split(SegmentList before, Capture.PromptMarker marker, SegmentList after) {
    }

    /** Splits at the nearest marker whose tag is {@code tag}, or returns null if there is none. */
    @TruffleBoundary
    static Split splitAt(SegmentList list, Object tag) {
        java.util.ArrayList<Object> before = new java.util.ArrayList<>();
        for (SegmentList l = list; l != null; l = l.tail) {
            if (l.head instanceof Capture.PromptMarker marker && Capture.sameTag(marker.tag(), tag)) {
                SegmentList prefix = null;
                for (int i = before.size() - 1; i >= 0; i--) {
                    prefix = new SegmentList(before.get(i), prefix);
                }
                return new Split(prefix, marker, l.tail);
            }
            before.add(l.head);
        }
        return null;
    }
}
