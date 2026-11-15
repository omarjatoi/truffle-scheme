package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.Test;

/**
 * M2-03: prompts with tags (design.md D4). The internal procedures, for the prelude's %guard (M3):
 *
 * <ul>
 * <li>{@code (%call-with-prompt tag thunk)} calls thunk inside a prompt;</li>
 * <li>{@code (%capture-to-prompt tag receiver)} captures the continuation up to the nearest prompt
 * with that tag, aborts to it, and calls receiver there with that (composable) continuation.</li>
 * </ul>
 */
public class PromptTest {

    private static final String SCHEME = "scheme";

    private static long evalLong(String program) {
        try (Context context = Context.create(SCHEME)) {
            return context.eval(SCHEME, program).asLong();
        }
    }

    private static String output(String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            context.eval(SCHEME, program);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void promptReturnsTheThunksValue() {
        assertEquals(42L, evalLong("(%call-with-prompt 'p (lambda () 42))"));
        assertEquals(43L, evalLong("(+ 1 (%call-with-prompt 'p (lambda () 42)))"));
    }

    @Test
    public void captureAbortsToThePrompt() {
        // The receiver's value is the prompt's value: (+ 10 []) is abandoned.
        assertEquals(6L, evalLong("(+ 1 (%call-with-prompt 'p (lambda () (+ 10 (%capture-to-prompt 'p (lambda (k) 5))))))"));
    }

    @Test
    public void capturedContinuationsAreComposable() {
        // k is (lambda (v) (+ 10 v)) up to the prompt; invoking it returns to the caller.
        assertEquals(26L, evalLong("(+ 1 (%call-with-prompt 'p (lambda () (+ 10 (%capture-to-prompt 'p (lambda (k) (k (k 5))))))))"));
        // ... also after the prompt has returned, and more than once.
        assertEquals("0 105 106", output("(define kk #f)" +
                        "(display (%call-with-prompt 'p (lambda () (+ 100 (%capture-to-prompt 'p (lambda (k) (set! kk k) 0))))))" +
                        "(display \" \") (display (kk 5)) (display \" \") (display (+ 1 (kk 5)))"));
    }

    @Test
    public void nestedPromptsWithDifferentTags() {
        String nested = "(%call-with-prompt 'outer (lambda () (* 2 (%call-with-prompt 'inner (lambda () (+ 1 %CAPTURE))))))";
        // Abort to the inner prompt: its value 100 is doubled by the outer computation.
        assertEquals(200L, evalLong(nested.replace("%CAPTURE", "(%capture-to-prompt 'inner (lambda (k) 100))")));
        // Abort to the outer prompt, past the inner one and past (* 2 []).
        assertEquals(100L, evalLong(nested.replace("%CAPTURE", "(%capture-to-prompt 'outer (lambda (k) 100))")));
        // k up to the inner prompt: (+ 1 v). k up to the outer prompt crosses the inner prompt:
        // (* 2 (inner-prompt (+ 1 v))), and invoking it reinstates the inner prompt.
        assertEquals(22L, evalLong(nested.replace("%CAPTURE", "(%capture-to-prompt 'inner (lambda (k) (k 10)))")));
        assertEquals(22L, evalLong(nested.replace("%CAPTURE", "(%capture-to-prompt 'outer (lambda (k) (k 10)))")));
        // The reinstated inner prompt is live: when k2 is re-entered with 99, the resumed code aborts to
        // 'inner, and that prompt exists only because invoking k2 reinstated it (the 1 + is skipped).
        assertEquals(1000L, evalLong("(define k2 #f)" +
                        "(define (run) (%call-with-prompt 'outer (lambda () (* 2 (%call-with-prompt 'inner (lambda ()" +
                        "  (+ 1 ((lambda (v) (if (= v 99) (%capture-to-prompt 'inner (lambda (k) 500)) v))" +
                        "        (%capture-to-prompt 'outer (lambda (k) (set! k2 k) 0))))))))))" +
                        "(run)" +
                        "(k2 99)"));
    }

    @Test
    public void callccCrossesPrompts() {
        // A full continuation captured inside a prompt includes the prompt; re-entering it works.
        assertEquals("2 11 ", output("(define saved #f) (define n 0)" +
                        "(define (f) (%call-with-prompt 'p (lambda () (+ 1 (call/cc (lambda (k) (set! saved k) 1))))))" +
                        "(define (run) (display (f)) (display \" \") (set! n (+ n 1)) (if (< n 2) (saved 10) 'done))" +
                        "(run)"));
        // Escaping with call/cc out of a prompt.
        assertEquals(7L, evalLong("(call/cc (lambda (k) (%call-with-prompt 'p (lambda () (+ 1 (k 7))))))"));
    }

    @Test
    public void captureWithoutAMatchingPromptIsAnError() {
        try (Context context = Context.create(SCHEME)) {
            PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval(SCHEME, "(%capture-to-prompt 'nope (lambda (k) 1))"));
            assertTrue(e.getMessage(), e.getMessage().contains("no prompt"));
        }
    }

    @Test
    public void promptsInLoops() {
        // Prompts installed in a self tail loop don't accumulate.
        assertEquals(100000L, evalLong("(define (loop i) (if (= i 100000) i (loop (%call-with-prompt 'p (lambda () (+ i 1)))))) (loop 0)"));
        assertEquals(100000L, evalLong("(define (loop i) (if (= i 100000) i (loop (%call-with-prompt 'p (lambda () (%capture-to-prompt 'p (lambda (k) (k (+ i 1))))))))) (loop 0)"));
    }
}
