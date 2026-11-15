package scheme.runtime;

/**
 * An immutable list of continuation segments, innermost first. Continuations share tails, so a
 * capture shares the segments that are already on the heap (design.md "Continuations"). null is the
 * empty list.
 */
public final class SegmentList {

    public final Capture.Segment head;
    public final SegmentList tail;

    public SegmentList(Capture.Segment head, SegmentList tail) {
        this.head = head;
        this.tail = tail;
    }
}
