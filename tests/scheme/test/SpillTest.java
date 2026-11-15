package scheme.test;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.junit.Test;

/**
 * M2-05: stack-depth spill (design.md "Continuations"). Non-tail recursion far deeper than a Java
 * stack can hold: past a depth threshold a call site captures up to the root prompt instead of
 * calling, and the root prompt makes the call on a fresh Java stack.
 */
public class SpillTest {

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
    public void deepNonTailRecursion() {
        assertEquals(1_000_000L, evalLong("(define (count i) (if (= i 1000000) 0 (+ 1 (count (+ i 1))))) (count 0)"));
        // Building a list non-tail-recursively, then walking it non-tail-recursively.
        assertEquals(1_000_000L, evalLong("(define (build i) (if (= i 1000000) '() (cons i (build (+ i 1)))))" +
                        "(define (len l) (if (null? l) 0 (+ 1 (len (cdr l))))) (len (build 0))"));
    }

    @Test
    public void captureAndReentryAcrossSpilledFrames() {
        // The continuation captured at the bottom spans a million spilled frames.
        assertEquals("1000000 1000010 ", output("(define saved #f) (define n 0)" +
                        "(define (deep i) (if (= i 1000000) (call/cc (lambda (k) (set! saved k) 0)) (+ 1 (deep (+ i 1)))))" +
                        "(define (run) (display (deep 0)) (display \" \") (set! n (+ n 1)) (if (= n 1) (saved 10) 'done))" +
                        "(run)"));
        // Escaping from the bottom of a deep recursion.
        assertEquals(-1L, evalLong("(define (deep i k) (if (= i 1000000) (k -1) (+ 1 (deep (+ i 1) k)))) (call/cc (lambda (k) (deep 0 k)))"));
    }

    @Test
    public void spillingThroughPrompts() {
        assertEquals(1_000_001L, evalLong("(define (count i) (if (= i 1000000) 0 (+ 1 (count (+ i 1)))))" +
                        "(+ 1 (%call-with-prompt 'p (lambda () (count 0))))"));
        assertEquals(7L, evalLong("(define (deep i) (if (= i 1000000) (%capture-to-prompt 'p (lambda (k) 7)) (+ 1 (deep (+ i 1)))))" +
                        "(%call-with-prompt 'p (lambda () (deep 0)))"));
    }

    @Test
    public void spillingThroughDynamicWind() {
        // A dynamic-wind at every level; an escape from the bottom runs every after-thunk.
        assertEquals(10_000L, evalLong("(define afters 0)" +
                        "(define (deep i k) (if (= i 10000) (k 'out) (dynamic-wind (lambda () #t) (lambda () (deep (+ i 1) k)) (lambda () (set! afters (+ afters 1))))))" +
                        "(call/cc (lambda (k) (deep 0 k))) afters"));
    }
}
