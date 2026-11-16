package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.graalvm.polyglot.Context;
import org.junit.Assume;
import org.junit.Test;

/**
 * M5-10: each non-tail call site performs the tail calls that come back to it through an inline
 * cache of its own, so a tail-called procedure can be inlined into the site; call sites report
 * polymorphism, so Truffle splits a higher-order procedure for each of its callers; and a closure
 * that is a compilation constant has constant captured values. Tail calls still run in constant
 * space (tests/spec/tail-* check that under small JVM limits).
 */
public class TailCallCacheTest {

    private static final String SCHEME = "scheme";

    private static String run(String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            context.eval(SCHEME, program);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void check(String defs, String expected, String expr) {
        String out = run(defs + "\n(write '" + expected + ") (display \"|\") (write " + expr + ")");
        String[] parts = out.split("\\|", 2);
        assertEquals(expr, parts[0], parts[1]);
    }

    /**
     * What the engine logs while it runs {@code program}, with the given engine options (and the
     * compiler, synchronously, once a root has been called 100 times). Skips the test when the
     * runtime has no compiler: then the options don't exist.
     */
    private static String engineLog(String program, String... options) throws IOException {
        Path log = Files.createTempFile("tail-call-cache", ".log");
        Context.Builder builder = Context.newBuilder(SCHEME).allowExperimentalOptions(true).option("log.file", log.toString()).out(new ByteArrayOutputStream());
        builder.option("engine.Compilation", "true").option("engine.CompileImmediately", "false").option("engine.BackgroundCompilation", "false");
        builder.option("engine.MultiTier", "false").option("engine.SingleTierCompilationThreshold", "100");
        for (int i = 0; i < options.length; i += 2) {
            builder.option(options[i], options[i + 1]);
        }
        Context context;
        try {
            context = builder.build();
        } catch (IllegalArgumentException e) {
            Assume.assumeNoException("no optimizing Truffle runtime", e);
            throw e;
        }
        try (Context c = context) {
            c.eval(SCHEME, program);
        }
        try {
            return Files.readString(log);
        } finally {
            Files.delete(log);
        }
    }

    @Test
    public void tailCallSitesSeeingManyProcedures() {
        // Each site below gets more tail-called procedures than its cache holds: closures of several
        // lambdas, closures of one lambda over different values, and primitives that aren't run in
        // place (apply, call/cc), in an order that switches back and forth.
        String defs = "(define (adder n) (lambda (x) (+ x n)))" +
                        "(define (via f x) (f x))" +
                        "(define (twice x) (* 2 x)) (define (neg x) (- x))" +
                        "(define (collect fs x) (if (null? fs) '() (cons (via (car fs) x) (collect (cdr fs) x))))";
        String fs = "(list (adder 1) twice (adder 2) neg (lambda (x) (apply + (list x x))) (lambda (x) (call/cc (lambda (k) (k (+ x 100)))))" +
                        " (adder 3) (lambda (x) (list x)) twice)";
        check(defs, "(11 20 12 -10 20 110 13 (10) 20)", "(collect " + fs + " 10)");
        check(defs, "((2 2) (-3 6))", "(list (collect (list (adder 1) twice) 1) (collect (list neg twice) 3))");
    }

    @Test
    public void tailCallsToRedefinedProcedures() {
        try (Context context = Context.create(SCHEME)) {
            context.eval(SCHEME, "(define (g x) (+ x 1)) (define (f x) (g x)) (define (h x) (* 10 (f x)))");
            for (int i = 0; i < 1000; i++) {
                assertEquals(20L, context.eval(SCHEME, "(h 1)").asLong());
            }
            context.eval(SCHEME, "(set! g (lambda (x) (- x 1)))");
            assertEquals(0L, context.eval(SCHEME, "(h 1)").asLong());
            context.eval(SCHEME, "(set! g car)");
            assertEquals(10L, context.eval(SCHEME, "(h '(1))").asLong());
            context.eval(SCHEME, "(set! g 5)");
            assertEquals("caught", context.eval(SCHEME, "(guard (e (#t 'caught)) (h 1))").toString());
        }
    }

    @Test
    public void tailCallErrorsAreRaisedAtTheCallSite() {
        check("(define (f x) (x 1))", "(\"not a procedure: 5\" 2)",
                        "(list (guard (e ((error-object? e) (error-object-message e))) (f 5)) (f (lambda (y) (+ y 1))))");
        check("(define (f) (g 1 2)) (define (g x) x)", "caught", "(guard (e (#t 'caught)) (f))");
    }

    @Test
    public void tailCallChainsRunInConstantSpace() {
        // A million tail calls between procedures (no self-loop), from one non-tail call site.
        check("(define (ev? n) (if (= n 0) #t (od? (- n 1)))) (define (od? n) (if (= n 0) #f (ev? (- n 1))))",
                        "(#t #f)", "(list (ev? 1000000) (ev? 999999))");
        // ... through a prompt, and through a cycle of three procedures of the same lambda.
        check("(define (ev? n) (if (= n 0) #t (od? (- n 1)))) (define (od? n) (if (= n 0) #f (ev? (- n 1))))",
                        "#t", "(%call-with-prompt 'p (lambda () (ev? 1000000)))");
        check("(define (make next) (lambda (n) (if (= n 0) 'done ((next) (- n 1)))))" +
                        "(define a (make (lambda () b))) (define b (make (lambda () c))) (define c (make (lambda () a)))",
                        "done", "(a 1000000)");
    }

    @Test
    public void constantClosuresStillSeeAssignments() {
        // A global closure is a compilation constant; its captured values fold. What set! assigns
        // lives in a box (design.md D2), so it is the box that's constant, never the value.
        check("(define counter (let ((n 0)) (lambda () (set! n (+ n 1)) n)))" +
                        "(define (count-to k) (let loop ((i 1)) (if (= i k) (counter) (begin (counter) (loop (+ i 1))))))",
                        "(20000 20001)", "(list (count-to 20000) (counter))");
        // Record accessors capture their record type and field index.
        check("(define-record-type point (make-point x y) point? (x point-x set-point-x!) (y point-y))" +
                        "(define (sum-x n p) (let loop ((i 0) (s 0)) (if (= i n) s (begin (set-point-x! p i) (loop (+ i 1) (+ s (point-x p)))))))",
                        "199990000", "(sum-x 20000 (make-point 0 0))");
    }

    @Test
    public void tailCalledProceduresAreInlined() throws IOException {
        // tail-callee is only ever reached by a tail call (from f), so before M5-10 the trampoline
        // called it indirectly, and it was never inlined.
        String log = engineLog("(define (tail-callee x) (+ x 1)) (define (f x) (tail-callee x)) (define (caller x) (* 2 (f x)))" +
                        "(let loop ((i 0) (s 0)) (if (= i 20000) s (loop (+ i 1) (+ s (caller i)))))",
                        "compiler.TraceInlining", "true");
        assertTrue(log, Pattern.compile("Inlined\\s+tail-callee\\b").matcher(log).find());
    }

    @Test
    public void higherOrderProceduresAreSplit() throws IOException {
        // twice is called from five sites, each with a procedure of its own: more than its call
        // site's cache holds, so the site reports the polymorphism, and Truffle splits twice for
        // each caller. (Since M7-01 a site reports only when it goes megamorphic: up to the cache
        // limit it inlines every procedure it has seen; StableWarmupTest checks that.)
        String log = engineLog("(define (twice f x) (f (f x)))" +
                        "(define (a x) (twice (lambda (y) (+ y 1)) x)) (define (b x) (twice (lambda (y) (* y 2)) x))" +
                        "(define (c x) (twice (lambda (y) (- y 3)) x)) (define (d x) (twice (lambda (y) (+ y 4)) x))" +
                        "(define (e x) (twice (lambda (y) (* y 5)) x))" +
                        "(let loop ((i 0) (s 0)) (if (= i 2000) s (loop (+ i 1) (+ s (a i) (b i) (c i) (d i) (e i)))))",
                        "engine.TraceSplitting", "true");
        assertTrue(log, Pattern.compile("split\\s+\\S+\\s+twice\\b").matcher(log).find());
    }
}
