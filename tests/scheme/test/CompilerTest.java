package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.Test;

/** M1-05: the core forms, closures, the call protocol and tail calls, end to end through the polyglot API. */
public class CompilerTest {

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

    private static PolyglotException error(String program) {
        try (Context context = Context.create(SCHEME)) {
            PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval(SCHEME, program));
            assertTrue(e.getMessage(), e.isGuestException());
            return e;
        }
    }

    @Test
    public void coreForms() {
        assertEquals(42L, evalLong("42"));
        assertEquals(3L, evalLong("(+ 1 2)"));
        assertEquals("(a b)", output("(display '(a b))"));
        assertEquals(1L, evalLong("(if #t 1 2)"));
        assertEquals(2L, evalLong("(if #f 1 2)"));
        assertEquals(1L, evalLong("(if '() 1 2)"));
        assertEquals(6L, evalLong("(define x 5) (set! x (+ x 1)) x"));
        assertEquals(3L, evalLong("(begin 1 2 3)"));
        assertEquals(10L, evalLong("((lambda (a b) (+ a b)) 4 6)"));
        assertEquals("(2 3)", output("(display ((lambda (a . rest) rest) 1 2 3))"));
        assertEquals("(1 2)", output("(display ((lambda args args) 1 2))"));
        assertEquals(120L, evalLong("(letrec* ((f (lambda (n) (if (= n 0) 1 (* n (f (- n 1))))))) (f 5))"));
        assertEquals(3L, evalLong("(import (scheme base) (scheme write)) (+ 1 2)"));
    }

    @Test
    public void closuresAndAssignment() {
        // A counter: the captured variable is assigned, so it lives in a box shared by the closure.
        assertEquals(3L, evalLong("(define (make-counter) (define n 0) (lambda () (set! n (+ n 1)) n))" +
                        "(define c (make-counter)) (c) (c) (c)"));
        // Two closures share one box.
        assertEquals(11L, evalLong("(define (pair) (define v 1) (cons (lambda () v) (lambda (x) (set! v x))))" +
                        "(define p (pair)) ((cdr p) 11) ((car p))"));
        // Three levels of capture.
        assertEquals(6L, evalLong("((((lambda (a) (lambda (b) (lambda (c) (+ a b c)))) 1) 2) 3)"));
        // Each self-loop iteration gets fresh parameters: closures capture their own iteration's value.
        assertEquals("(2 1 0)", output("(define (collect n acc) (if (= n 3) acc (collect (+ n 1) (cons (lambda () n) acc))))" +
                        "(define (run fs) (if (null? fs) '() (cons ((car fs)) (run (cdr fs)))))" +
                        "(display (run (collect 0 '())))"));
        // ... also when the parameter is assigned (boxed).
        assertEquals("(2 1 0)", output("(define (collect n acc) (set! n n) (if (= n 3) acc (collect (+ n 1) (cons (lambda () n) acc))))" +
                        "(define (run fs) (if (null? fs) '() (cons ((car fs)) (run (cdr fs)))))" +
                        "(display (run (collect 0 '())))"));
        // Internal definitions are letrec*: mutual recursion.
        assertEquals(1L, evalLong("(define (f n) (define (ev? n) (if (= n 0) #t (od? (- n 1)))) (define (od? n) (if (= n 0) #f (ev? (- n 1)))) (if (ev? n) 1 0)) (f 10)"));
    }

    @Test
    public void recursionAndTailCalls() {
        assertEquals(6765L, evalLong("(define (fib n) (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2))))) (fib 20)"));
        // Self tail call: a loop, constant space.
        assertEquals(10_000_000L, evalLong("(define (loop i) (if (= i 10000000) i (loop (+ i 1)))) (loop 0)"));
        // Mutual tail calls go through the trampoline: no Java stack growth.
        assertEquals(1L, evalLong("(define (ev? n) (if (= n 0) 1 (od? (- n 1)))) (define (od? n) (if (= n 0) 0 (ev? (- n 1)))) (ev? 1000000)"));
        // A tail call to a different procedure with the same arity is not a loop.
        assertEquals(7L, evalLong("(define (g x) (+ x 1)) (define (f x) (g x)) (f 6)"));
        // Fixnum overflow promotes to a bignum.
        assertEquals("9223372036854775808", output("(display (+ 9223372036854775807 1))"));
    }

    @Test
    public void globalsCanBeRedefined() {
        assertEquals(2L, evalLong("(define (f) 1) (define (g) (f)) (g) (g) (define (f) 2) (g)"));
        assertEquals(5L, evalLong("(define x 1) (define (get) x) (get) (set! x 5) (get)"));
    }

    @Test
    public void output() {
        assertEquals("hello 42\n\"s\"", output("(display \"hello \") (display 42) (newline) (write \"s\")"));
    }

    @Test
    public void errors() {
        assertTrue(error("(car 1)").getMessage().contains("car: argument 1 is not a pair: 1"));
        assertTrue(error("undefined-variable").getMessage().contains("unbound variable: undefined-variable"));
        assertTrue(error("((lambda (x) x))").getMessage().contains(": expected 1 argument, got 0"));
        assertTrue(error("(define (f x) x) (f)").getMessage().contains("f: expected 1 argument, got 0"));
        assertTrue(error("(car)").getMessage().contains("car: expected 1 argument, got 0"));
        assertTrue(error("(1 2)").getMessage().contains("application: not a procedure: 1"));
        assertTrue(error("(set! nope 1)").getMessage().contains("unbound variable: nope"));
        assertTrue(error("(lambda (x x) x)").getMessage().contains("syntax error"));
        assertTrue(error("(define-syntax foo 1)").getMessage().contains("not a transformer"));
        assertTrue(error("(+ 1 \"a\")").getMessage().contains("+: argument 2 is not a number: \"a\""));
    }
}
