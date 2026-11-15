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
 * M2-02: call/cc capture (design.md "Continuations") and re-entry from copied frames. Unit tests run
 * both with the JIT in background mode and compiling immediately (mx tests unittest, unittest-compile).
 */
public class ContinuationTest {

    private static final String SCHEME = "scheme";

    private static String output(String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            context.eval(SCHEME, program);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static long evalLong(String program) {
        try (Context context = Context.create(SCHEME)) {
            return context.eval(SCHEME, program).asLong();
        }
    }

    @Test
    public void returnsNormally() {
        assertEquals(3L, evalLong("(+ 1 (call/cc (lambda (k) 2)))"));
        assertEquals(3L, evalLong("(+ 1 (call-with-current-continuation (lambda (k) 2)))"));
    }

    @Test
    public void escapes() {
        assertEquals(6L, evalLong("(+ 1 (call/cc (lambda (k) (+ 10 (k 5)))))"));
        // Escape from deep inside a recursion: the frames in between are abandoned.
        assertEquals(-1L, evalLong("(define (find-neg l k) (if (null? l) 0 (if (< (car l) 0) (k (car l)) (+ 1 (find-neg (cdr l) k)))))" +
                        "(call/cc (lambda (k) (find-neg '(1 2 3 -1 5) k)))"));
        // An escape that returns through a non-tail call in another procedure.
        assertEquals(42L, evalLong("(define (f k) (* 2 (k 42))) (+ 0 (call/cc (lambda (k) (f k))))"));
    }

    @Test
    public void reentersMultipleTimes() {
        // The frames of g (and of the program) are resumed three times, from copies.
        assertEquals("1000\n1001\n1002\n", output("(define saved #f) (define count 0)" +
                        "(define (g) (+ 1000 (call/cc (lambda (k) (set! saved k) 0))))" +
                        "(define (run) (display (g)) (newline) (set! count (+ count 1)) (if (< count 3) (saved count) 'done))" +
                        "(run)"));
        // Pending operand-stack values survive re-entry: (+ 1 (* 2 []))
        assertEquals("7\n21\n", output("(define k #f) (define once #f)" +
                        "(define (run) (display (+ 1 (* 2 (call/cc (lambda (c) (set! k c) 3))))) (newline)" +
                        "  (if once 'done (begin (set! once #t) (k 10))))" +
                        "(run)"));
    }

    /** design.md D6: re-entering a continuation captured in one top-level form carries on through the following forms. */
    @Test
    public void topLevelReentryContinuesThroughFollowingForms() {
        assertEquals("101\n101\n102\ndone\n", output("(define r #f) (define n 0)" +
                        "(display (+ 100 (call/cc (lambda (k) (set! r k) 1)))) (newline)" +
                        "(set! n (+ n 1))" +
                        "(if (< n 3) (r n))" +
                        "(display \"done\") (newline)"));
    }

    @Test
    public void captureDuringResumeKeepsTheOuterSegments() {
        // k1 is re-entered; inside that resume, k2 is captured. Invoking k2 must also run the rest of
        // k1's continuation (the segments not yet resumed when k2 was captured).
        assertEquals("(a 1)(a 2)(b 1)(b 2)", output("(define k1 #f) (define k2 #f) (define phase 0)" +
                        "(define (inner tag) (call/cc (lambda (k) (set! k2 k) 1)))" +
                        "(define (outer) (display (list (call/cc (lambda (k) (set! k1 k) 'a)) (inner 'x))))" +
                        "(define (run) (outer)" +
                        "  (set! phase (+ phase 1))" +
                        "  (if (= phase 1) (k2 2) (if (= phase 2) (k1 'b) (if (= phase 3) (k2 2) 'done))))" +
                        "(run)"));
    }

    @Test
    public void callccInTailPosition() {
        assertEquals(5L, evalLong("(define (tc f) (call/cc f)) (+ 2 (tc (lambda (k) (k 3))))"));
        assertEquals(9L, evalLong("(define (loop i) (if (= i 3) (call/cc (lambda (k) (k 9))) (loop (+ i 1)))) (loop 0)"));
    }

    @Test
    public void generator() {
        // A generator over a list, built from two continuations (no for-each needed).
        assertEquals("1 2 3 done", output("(define (make-gen l)" +
                        "  (define return #f)" +
                        "  (define (walk l) (if (null? l) (return 'done) (begin (call/cc (lambda (resume) (set! walk-k resume) (return (car l)))) (walk (cdr l)))))" +
                        "  (define walk-k #f)" +
                        "  (lambda () (call/cc (lambda (r) (set! return r) (if walk-k (walk-k #f) (walk l))))))" +
                        "(define g (make-gen '(1 2 3)))" +
                        "(display (g)) (display \" \") (display (g)) (display \" \") (display (g)) (display \" \") (display (g))"));
    }

    @Test
    public void continuationsCaptureTheDynamicEnvironment() {
        // The parameterization slot carries the marker: winders are real dynamic-wind records (M2-04).
        assertEquals("(a)", output("(define saved #f) (define done #f)" +
                        "(define (run)" +
                        "  (%set-dynamic-env! (%make-dynamic-env '() '(a) '()))" +
                        "  (call/cc (lambda (k) (set! saved k)))" +
                        "  (if done (display (%dynamic-env-parameterization (%current-dynamic-env)))" +
                        "      (begin (set! done #t) (%set-dynamic-env! (%make-dynamic-env '() '(b) '())) (saved 0))))" +
                        "(run)"));
    }

    @Test
    public void multipleValuesAndErrors() {
        assertEquals("#<values 1 2>", output("(display (call/cc (lambda (k) (k 1 2))))"));
        try (Context context = Context.create(SCHEME)) {
            PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval(SCHEME, "(call/cc 5)"));
            assertTrue(e.getMessage(), e.getMessage().contains("not a procedure"));
        }
        assertEquals("#<procedure continuation>", output("(call/cc (lambda (k) (display k)))"));
    }
}
