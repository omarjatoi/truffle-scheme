package scheme.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * What a suspended frame is resumed with, from a copy, to end it for tools (design.md D13, "Ending a
 * guard's frames for tools"): {@code Resumed} ends the frames it called first, nested inside it as
 * they were on the stack, then throws {@link #exception} from its call site, which every tag around
 * it takes for an exceptional return, as for an escape's {@link AbandonedFrames}.
 *
 * <p>A guard that catches a raise has suspended the frames between the raise and itself into a
 * composable continuation (D4), and drops it. Suspended, each frame is a generator's to the
 * debugger: a step that started in one waits for its resumption ({@code RootNode#isSameFrame}),
 * which would never come. Resumed this way, the frame ends instead, and the step goes on. Nested,
 * the frames end in the order they would have, had the raise unwound them: so a frame the step was
 * over (one the stepping frame called) resumes deeper than the stepping frame and doesn't stop, and
 * a frame outside it resumes before it, while the step still waits, and doesn't stop either.
 *
 * <p>At most {@link #NESTED} frames, the innermost, end nested: a Java call each (AGENTS.md rule 4).
 * The frames outside them end first, one after the other, outermost first.
 */
final class Ending {

    /** How many of the innermost frames end nested, each inside the one that called it. */
    static final int NESTED = 64;

    final AbandonedFrames exception;
    /** The frames this one called, innermost first: {@code inner[0..count)}. */
    private final Capture.Segment[] inner;
    private final int count;

    private Ending(AbandonedFrames exception, Capture.Segment[] inner, int count) {
        this.exception = exception;
        this.inner = inner;
        this.count = count;
    }

    /** Ends {@code frames}, innermost first, each from a copy of its frame. */
    @TruffleBoundary
    static void end(Capture.Segment[] frames) {
        AbandonedFrames exception = AbandonedFrames.ending();
        int nested = Math.min(frames.length, NESTED);
        for (int i = frames.length - 1; i >= nested; i--) {
            resume(frames[i], new Ending(exception, frames, 0), exception);
        }
        resume(frames[nested - 1], new Ending(exception, frames, nested - 1), exception);
    }

    /** The frames this one called end inside it, from {@code Resumed}. */
    @TruffleBoundary
    void endInner() {
        if (count > 0) {
            resume(inner[count - 1], new Ending(exception, inner, count - 1), exception);
        }
    }

    /** Resumes {@code segment} from a copy with {@code ending}, which ends it by throwing {@code exception}. */
    private static void resume(Capture.Segment segment, Ending ending, AbandonedFrames exception) {
        try {
            segment.root().getCallTarget().call(PromptDriver.copy(segment.frame()), ending);
        } catch (AbandonedFrames e) {
            if (e == exception) {
                return;
            }
            throw e;
        }
        throw new IllegalStateException("an ended frame returned instead of throwing");
    }
}
