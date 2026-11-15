package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.graalvm.polyglot.Context;
import org.junit.Test;

/**
 * M2-06: torture mode ({@code -Dscheme.torture=true}, {@code mx r7rs-unittest --torture}) captures
 * and resumes at every non-tail call of a Scheme procedure. Every other test in this suite also runs
 * under torture in {@code mx tests torture}; this one checks that torture really happens.
 */
public class TortureTest {

    private static final boolean TORTURE = Boolean.getBoolean("scheme.torture");

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
}
