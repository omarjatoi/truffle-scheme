package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.Test;

/**
 * Promises (R7RS §4.2.5, design.md D1, D9) are a type of their own: no other type's predicate
 * holds for one, no pair or record procedure takes one, {@code write} shows {@code #<promise>},
 * and interop sees a {@code promise}. Forcing keeps §7.3's semantics: a delay-force chain forces
 * in constant space, a promise forced again while it is being forced takes the first forcing's
 * value, and {@code make-promise} of a promise is that promise.
 */
public class PromiseTest {

    private static final String SCHEME = "scheme";

    private static String run(String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            context.eval(SCHEME, program);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String write(String expr) {
        return run("(import (scheme base) (scheme write) (scheme lazy)) (write " + expr + ")");
    }

    @Test
    public void promisesAreTheirOwnType() {
        String predicates = "(list pair? list? vector? procedure? string? symbol? null? boolean? char? number? bytevector? error-object? eof-object?)";
        assertEquals("(#f #f #f #f #f #f #f #f #f #f #f #f #f)",
                        write("(let ((p (delay 1))) (map (lambda (pred) (pred p)) " + predicates + "))"));
        assertEquals("(#f #f #f #f #f #f #f #f #f #f #f #f #f)",
                        write("(let ((p (make-promise 1))) (map (lambda (pred) (pred p)) " + predicates + "))"));
        assertEquals("(#f #f #f #f #f #f #f #f #f #f #f #f #f)",
                        write("(let ((p (delay-force (delay 1)))) (map (lambda (pred) (pred p)) " + predicates + "))"));
        // promise? holds only for promises: not for a pair that looks like the old representation.
        assertEquals("(#t #t #t #f #f #f #f #f)",
                        write("(list (promise? (delay 1)) (promise? (make-promise 1)) (promise? (delay-force (delay 1))) (promise? 1) (promise? '()) (promise? (cons 1 2)) (promise? (lambda () 1)) (promise? (vector)))"));
        // A record type's predicate doesn't hold for a promise, and promise? doesn't for a record.
        assertEquals("(#f #f)", write("(let () (define-record-type promise (kons) promise*?) (list (promise*? (delay 1)) (promise? (kons))))"));
    }

    @Test
    public void pairProceduresRejectPromises() {
        for (String op : new String[]{"(car p)", "(cdr p)", "(set-car! p 1)", "(set-cdr! p 1)", "(length p)", "(vector-ref p 0)"}) {
            assertEquals(op, "error", write("(let ((p (delay 1))) (guard (e (#t 'error)) " + op + " 'no-error))"));
        }
    }

    @Test
    public void writeShowsAPromise() {
        assertEquals("#<promise>", write("(delay 1)"));
        assertEquals("#<promise>", write("(make-promise 1)"));
        assertEquals("(#<promise> #<promise>)", write("(let ((p (delay 1))) (force p) (list p (make-promise p)))"));
        assertEquals("#<promise>", run("(import (scheme base) (scheme write) (scheme lazy)) (display (delay-force (delay 1)))"));
    }

    @Test
    public void forcing() {
        assertEquals("(3 3)", write("(let ((p (delay (+ 1 2)))) (list (force p) (force p)))"));
        assertEquals("(5 5 7 #t)", write("(let ((p (make-promise 5))) (list (force p) (force (delay-force p)) (force 7) (eq? p (make-promise p))))"));
        // The body runs once, however often the promise is forced.
        assertEquals("(1 x x)", write("(let* ((n 0) (p (delay (begin (set! n (+ n 1)) 'x)))) (let* ((a (force p)) (b (force p))) (list n a b)))"));
        // Multiple values in a promise are an error in R7RS; one value is the common case.
        assertEquals("(a b)", write("(let ((p (delay (list 'a 'b)))) (force p))"));
    }

    @Test
    public void reentrantForceKeepsTheFirstValue() {
        // R7RS §4.2.5's example: forcing p again from inside its own forcing.
        assertEquals("(6 6)", write("""
                        (let ()
                          (define count 0)
                          (define x 5)
                          (define p
                            (delay (begin (set! count (+ count 1))
                                          (if (> count x) count (force p)))))
                          (let ((v (force p)))
                            (set! x 10)
                            (list v (force p))))
                        """));
        // The same through delay-force: the inner forcing completes first, and wins.
        assertEquals("(first first)", write("""
                        (let ()
                          (define again #t)
                          (define p
                            (delay-force
                             (if again
                                 (begin (set! again #f)
                                        (let ((v (force p))) (delay (list 'outer v))))
                                 (delay 'first))))
                          (list (force p) (force p)))
                        """));
    }

    @Test
    public void delayForceChainsForceInConstantSpace() {
        // §4.2.5's loop and stream-filter: a million promises, forced iteratively.
        assertEquals("done", write("(let () (define (loop n) (delay-force (if (= n 0) (delay 'done) (loop (- n 1))))) (force (loop 1000000)))"));
        assertEquals("1000000", write("""
                        (let ()
                          (define (from n) (delay (cons n (from (+ n 1)))))
                          (define (stream-filter p? s)
                            (delay-force
                             (if (null? (force s))
                                 (delay '())
                                 (let ((h (car (force s))) (t (cdr (force s))))
                                   (if (p? h) (delay (cons h (stream-filter p? t))) (stream-filter p? t))))))
                          (car (force (stream-filter (lambda (n) (= n 1000000)) (from 0)))))
                        """));
        assertEquals("ok", write("(let () (define (countdown n) (delay-force (if (= n 0) (make-promise 'ok) (countdown (- n 1))))) (force (countdown 1000000)))"));
    }

    @Test
    public void delayForceOfANonPromiseIsAnError() {
        assertEquals("error", write("(guard (e ((error-object? e) 'error)) (force (delay-force 5)))"));
    }

    @Test
    public void interopSeesAPromise() {
        try (Context context = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).build()) {
            Value promise = context.eval(SCHEME, "(import (scheme lazy)) (delay 1)");
            assertEquals("#<promise>", promise.toString());
            assertFalse(promise.hasArrayElements());
            assertFalse(promise.hasMembers());
            assertFalse(promise.canExecute());
            Value meta = promise.getMetaObject();
            assertTrue(meta.isMetaObject());
            assertEquals("promise", meta.getMetaSimpleName());
            assertEquals("promise", meta.getMetaQualifiedName());
            assertTrue(meta.isMetaInstance(promise));
            assertFalse(meta.isMetaInstance(context.eval(SCHEME, "(cons 1 2)")));
        }
    }
}
