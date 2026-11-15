package scheme.spike;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.graalvm.polyglot.Context;
import org.junit.Test;

/** M0-02 "Done when": multi-shot resume from copied frames. Run with {@code mx r7rs-spike [--compile]}. */
public class MultiShotResumeTest {

    /** What f1 returns on a fresh run where callcc returns v (box incremented once). */
    private static long expected(long v) {
        return v + 1_104_440L;
    }

    private static final long BOX_WEIGHT = 1_000_000L;

    private static String run(String program) {
        try (Context context = Context.create(SpikeLanguage.ID)) {
            return context.eval(SpikeLanguage.ID, program).toString();
        }
    }

    @Test
    public void freshRunMatchesTheProgramComments() {
        assertEquals(String.valueOf(expected(5)), run("direct 5"));
        assertEquals(String.valueOf(expected(7)), run("direct 7"));
    }

    /**
     * Capture across 3 frames, resume twice. Each resume must see the frame state at capture time
     * (locals, operand stack, block-scoped slots) and the same shared box.
     */
    @Test
    public void twoResumesAreIndependentAndShareTheBox() {
        String[] out = run("multishot 5 7").split(" ");
        assertEquals("segments=3", out[0]);
        assertEquals(String.valueOf(expected(5)), out[1]);
        assertEquals(String.valueOf(expected(7) + BOX_WEIGHT), out[2]);
        assertEquals("box=2", out[3]);
    }

    @Test
    public void resumeOrderDoesNotMatter() {
        String[] out = run("multishot 7 5 9").split(" ");
        assertEquals(String.valueOf(expected(7)), out[1]);
        assertEquals(String.valueOf(expected(5) + BOX_WEIGHT), out[2]);
        assertEquals(String.valueOf(expected(9) + 2 * BOX_WEIGHT), out[3]);
        assertEquals("box=3", out[4]);
    }

    /** Control: resuming the original frames twice is not independent. This is why we copy. */
    @Test
    public void resumingTheOriginalFrameTwiceLeaksState() {
        String[] out = run("control 5 7").split(" ");
        assertEquals(String.valueOf(expected(5)), out[1]);
        assertNotEquals(String.valueOf(expected(7) + BOX_WEIGHT), out[2]);
        System.out.println("control (no copy) run: " + String.join(" ", out));
    }
}
