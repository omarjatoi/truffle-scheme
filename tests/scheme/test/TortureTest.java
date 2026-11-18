package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.Test;

import scheme.runtime.SchemeLanguage;
import scheme.runtime.SchemeLanguage.Torture;

/**
 * M2-06: torture mode ({@code -Dpolyglot.scheme.Torture=shared}, {@code //tests:unit_torture}) captures
 * and resumes at every non-tail call of a Scheme procedure. Every other test in this suite also runs
 * under torture in the torture tests; this one checks that torture really happens.
 *
 * <p>Plain torture shares every capture, so every resume copies the frame and checks that the
 * original didn't change (AGENTS.md rule 3). Owned torture ({@code -Dpolyglot.scheme.Torture=owned},
 * {@code //tests:unit_torture_owned}) keeps its captures' frames owned, as a stack-depth
 * spill's are (design.md "Owned segments"), so the driver resumes them in place, once each, until a
 * continuation shares them; then they are copied and checked.
 */
public class TortureTest {

    private static final boolean TORTURE = SchemeLanguage.constant(SchemeLanguage.TORTURE) != Torture.OFF;
    private static final boolean OWNED = SchemeLanguage.constant(SchemeLanguage.TORTURE) == Torture.OWNED;

    @Test
    public void capturesAtEveryNonTailCallOnlyUnderTorture() {
        try (Context context = Context.create("scheme")) {
            // Each (g) is tortured at (f 1): g's frame and the program's are captured and resumed from
            // copies, so g then returns to the prompt, not back through the (g) call site, which
            // therefore has no return to torture. Primitive calls (+) and tail calls don't count.
            // Counted from before the program, so context initialization (the libraries) doesn't count.
            long before = context.eval("scheme", "(%torture-captures)").asLong();
            long captures = context.eval("scheme", "(define (f x) (+ x 1)) (define (g) (+ 0 (f 1))) (g) (g) (%torture-captures)").asLong() - before;
            if (TORTURE) {
                assertEquals(2L, captures);
            } else {
                assertEquals(0L, captures);
            }
            assertTrue(context.eval("scheme", "(define (fib n) (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2))))) (= (fib 15) 610)").asBoolean());
        }
    }

    /** {@code (%in-place-resumes)} and {@code (%copied-resumes)} so far. */
    private static long[] resumes(Context context) {
        Value v = context.eval("scheme", "(vector (%in-place-resumes) (%copied-resumes))");
        return new long[]{v.getArrayElement(0).asLong(), v.getArrayElement(1).asLong()};
    }

    @Test
    public void ownedTortureResumesInPlaceUntilAContinuationSharesTheFrames() {
        try (Context context = Context.create("scheme")) {
            context.eval("scheme", "(define (count i n) (if (= i n) 0 (+ 1 (count (+ i 1) n))))");
            // A recursion 20 deep, too shallow to spill: the innermost return is tortured, and the
            // driver resumes the 20 frames it suspended, and the program's (each then returns to
            // the driver, not through a call site, so it isn't tortured again).
            long[] before = resumes(context);
            assertEquals(20, context.eval("scheme", "(count 0 20)").asInt());
            long[] after = resumes(context);
            long inPlace = after[0] - before[0];
            long copied = after[1] - before[1];
            if (OWNED) {
                assertTrue("in place: " + inPlace, inPlace >= 21);
                assertEquals("copied", 0, copied);
            } else if (TORTURE) {
                assertEquals("in place", 0, inPlace);
                assertTrue("copied: " + copied, copied >= 21);
            } else {
                assertEquals("in place", 0, inPlace);
                assertEquals("copied", 0, copied);
            }
            // k is captured on the way back up, in frame 10, and re-entered twice after the
            // recursion has returned. Frames 0 to 9 were pending, owned, since the return to frame
            // 10 was tortured: k shares them, so each run resumes copies of them. (Owned torture
            // fails here if a continuation doesn't share the frames it reaches: the first run
            // would resume them in place, and a re-entry in place a second time.)
            Value results = context.eval("scheme", """
                            (define k #f)
                            (define (up i n)
                              (let ((a (* i 3)))
                                (+ a (let ((r (if (= i n) 0 (up (+ i 1) n))))
                                       (+ r (if (= i 10) (call/cc (lambda (c) (set! k c) 0)) 0))))))
                            (let ((runs 0) (results '()))
                              (let ((x (up 0 20)))
                                (set! results (cons x results))
                                (set! runs (+ runs 1))
                                (if (< runs 3) (k (* runs 100)) (reverse results))))
                            """);
            assertEquals("(630 730 830)", results.toString());
            if (TORTURE) {
                long[] end = resumes(context);
                assertTrue("copied: " + (end[1] - after[1]), end[1] - after[1] >= 2 * 10);
            }
        }
    }
}
