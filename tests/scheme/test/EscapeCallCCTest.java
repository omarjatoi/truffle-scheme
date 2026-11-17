package scheme.test;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.junit.Test;

import scheme.compiler.Expander;
import scheme.compiler.SyntacticEnvironment;
import scheme.runtime.GlobalEnvironment;
import scheme.runtime.value.SymbolTable;

/**
 * M5-04: escape-only {@code call/cc} (design.md "Continuations"). When the compiler proves that a
 * {@code (call/cc (lambda (k) ...))}'s k can only be invoked during the receiver's dynamic extent,
 * it calls {@code %call/ec} instead, which escapes by unwinding instead of capturing.
 *
 * <p>The analysis tests read the core AST ({@link Expander#show}): a rewritten call shows
 * {@code %call/ec}. The semantics tests run the same programs whichever way they compile, so they
 * also hold for the full capture; under {@code //tests:unit_torture} they run with a capture and resume
 * at every non-tail call, across live escape frames.
 */
public class EscapeCallCCTest {

    private static final String SCHEME = "scheme";

    private final SymbolTable symbols = new SymbolTable();
    private final SyntacticEnvironment top = new SyntacticEnvironment();

    /** The core AST of a unit; {@code closed}: an isolated program or library body (no other unit can redefine its globals). */
    private String show(String program, boolean closed) {
        return Expander.show(symbols, new GlobalEnvironment(), top, program, closed);
    }

    private void escapes(String program) {
        String ast = show(program, true);
        assertEquals(program + "\n=> " + ast, true, ast.contains("%call/ec"));
    }

    private void captures(String program) {
        String ast = show(program, true);
        assertEquals(program + "\n=> " + ast, false, ast.contains("%call/ec"));
    }

    private static String run(String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            context.eval(SCHEME, program);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** As an isolated program (a closed unit), then as text in the interaction environment. */
    private static void check(String expected, String body) {
        assertEquals(body, expected, run("(import (scheme base) (scheme write))\n" + body));
        assertEquals(body, expected, run(body));
    }

    // ------------------------------------------------------------------------------------------
    // The analysis

    @Test
    public void rewritesWhenKIsOnlyCalled() {
        assertEquals("(%call/ec (lambda (k.1) (k.1 1)))", show("(call/cc (lambda (k) (k 1)))", false));
        assertEquals("(%call/ec (lambda (k.1) (+ 1 (k.1 2))))", show("(call-with-current-continuation (lambda (k) (+ 1 (k 2))))", false));
        // k unused, called with several values, called in a test position.
        escapes("(call/cc (lambda (k) 1))");
        escapes("(call/cc (lambda (k) (k 1 2 3)))");
        escapes("(call/cc (lambda (k) (if (k 1) 2 3)))");
    }

    @Test
    public void rewritesWhenKIsPassedToKnownProceduresThatOnlyCallIt() {
        // ctak's shape: k goes to a global procedure of the unit, which calls it or passes it on.
        escapes("""
                        (define (ctak-aux k x y z)
                          (if (not (< y x))
                              (k z)
                              (call/cc (lambda (k) (ctak-aux k (call/cc (lambda (k) (ctak-aux k (- x 1) y z))) y z)))))
                        (call/cc (lambda (k) (ctak-aux k 3 2 1)))""");
        // fibc's shape: mutual passing between two globals.
        escapes("""
                        (define (addc x y k) (if (zero? y) (k x) (addc (+ x 1) (- y 1) k)))
                        (define (fibc x c) (if (zero? x) (c 0) (addc (call/cc (lambda (c) (fibc (- x 1) c))) 1 c)))
                        (call/cc (lambda (c) (fibc 5 c)))""");
        // An internal definition and a let-bound lambda are known in any unit.
        assertEquals(true, show("(lambda () (define (f k) (k 1)) (call/cc (lambda (k) (f k))))", false).contains("%call/ec"));
        assertEquals(true, show("(call/cc (lambda (k) ((lambda (j) (j 1)) k)))", false).contains("%call/ec"));
    }

    @Test
    public void keepsTheCaptureWhenKMayOutliveTheCall() {
        // Captured by a closure, stored, returned, or passed to an unknown procedure or a primitive.
        captures("(define (f l) (call/cc (lambda (k) (for-each (lambda (x) (if x (k x))) l))))");
        captures("(define saved #f) (call/cc (lambda (k) (set! saved k)))");
        captures("(call/cc (lambda (k) k))");
        captures("(call/cc (lambda (k) (k k)))");
        captures("(call/cc (lambda (k) (g k)))");
        captures("(call/cc (lambda (k) (vector k)))");
        captures("(call/cc (lambda (k) (set! k 1) k))");
        // Passed to a known procedure that lets it escape, directly or further down.
        captures("(define saved #f) (define (keep k) (set! saved k)) (call/cc (lambda (k) (keep k)))");
        captures("(define (f k) (list k)) (call/cc (lambda (k) (f k)))");
        captures("(define (f k) (g k)) (define (g k) (h k)) (call/cc (lambda (k) (f k)))");
        captures("(define (f k) (lambda () (k 1))) (call/cc (lambda (k) (f k)))");
        // A known procedure that a rest parameter or a wrong argument count would hand k to.
        captures("(define (f . ks) ((car ks) 1)) (call/cc (lambda (k) (f k)))");
        captures("(define (f k) (k 1)) (call/cc (lambda (k) (f k 2)))");
        // A global that isn't a lambda, is assigned, or is defined twice: not known.
        captures("(define f car) (call/cc (lambda (k) (f k)))");
        captures("(define (f k) (k 1)) (set! f (lambda (k) k)) (call/cc (lambda (k) (f k)))");
        captures("(define (f k) (k 1)) (define (f k) k) (call/cc (lambda (k) (f k)))");
        // A receiver that isn't a one-parameter lambda, and a call/cc that isn't the primitive.
        captures("(define (r k) (k 1)) (call/cc r)");
        captures("(call/cc (lambda k 1))");
        captures("(call/cc (lambda (k . more) 1))");
        captures("(lambda (call/cc) (call/cc (lambda (k) (k 1))))");
    }

    /*
     * The analysis tests see only the core forms: a let is written as what it expands to,
     * ((lambda (x ...) body) init ...), and a named let or a do loop as ((letrec* ((tag (lambda (x
     * ...) body))) tag) init ...). The semantics tests below run the same shapes as written.
     */

    @Test
    public void rewritesWhenKIsUsedInLambdasThatAreOnlyCalled() {
        // A do loop whose body calls k (puzzle's and quicksort's shape): a non-tail call of k keeps
        // it from being contified, so it is a lambda, but one only ever called, so it runs in the
        // receiver's extent. Local loops and helpers are known in any unit.
        assertEquals(true, show("""
                        (call/cc (lambda (return)
                                   ((letrec* ((loop (lambda (i) (if (= i 10) #f (begin (if (= i 5) (return i)) (loop (+ i 1)))))))
                                      loop)
                                    0)))""", false).contains("%call/ec"));
        // A named let over a variable of the procedure around call/cc.
        assertEquals(true, show("""
                        (lambda (l)
                          (call/cc (lambda (k)
                                     ((letrec* ((loop (lambda (l)
                                                        (if (pair? l)
                                                            (begin (if (negative? (car l)) (k (car l))) (loop (cdr l)))
                                                            0))))
                                        loop)
                                      l))))""", false).contains("%call/ec"));
        // A loop that calls k in tail position is contified (M7-02): its body is the receiver's.
        assertEquals(true, show("(call/cc (lambda (k) ((letrec* ((loop (lambda (i) (if (= i 5) (k i) (loop (+ i 1)))))) loop) 0)))", false).contains("%call/ec"));
        // Helpers bound by an internal definition, a let or a letrec*, called directly or from
        // each other, and a helper inside a helper.
        escapes("(lambda (x) (call/cc (lambda (k) (define (check x) (if (negative? x) (k x))) (check 1) (check x) 0)))");
        escapes("(call/cc (lambda (k) ((lambda (h) (+ 1 (h 2))) (lambda (x) (k x)))))");
        escapes("(call/cc (lambda (k) (letrec* ((even (lambda (n) (if (= n 0) (k 'even) (odd (- n 1))))) (odd (lambda (n) (if (= n 0) (k 'odd) (even (- n 1)))))) (+ 1 (even 7)))))");
        escapes("(call/cc (lambda (k) (define (outer x) (define (inner y) (k y)) (+ 1 (inner x))) (outer 1)))");
        // A let-bound helper is a known procedure too: k passed to it.
        escapes("(call/cc (lambda (k) ((lambda (h) (+ 1 (h k))) (lambda (j) (j 2)))))");
        // A loop inside a helper, and a helper called from inside a loop.
        escapes("""
                        (call/cc (lambda (k)
                                   (define (scan v)
                                     ((letrec* ((loop (lambda (i) (if (= i (vector-length v)) #f (begin (if (vector-ref v i) (k i)) (loop (+ i 1)))))))
                                        loop)
                                      0))
                                   (+ 1 (scan (vector #f #t)))))""");
        escapes("""
                        (call/cc (lambda (k)
                                   (define (leave x) (k x))
                                   ((letrec* ((loop (lambda (i) (begin (if (= i 3) (leave i)) (loop (+ i 1)))))) loop) 0)))""");
        // A lambda passed to a known procedure whose parameter is only called (maze's
        // vector-for-each-rev), directly or from that procedure's own loop.
        escapes("""
                        (define (each proc l) (if (pair? l) (begin (proc (car l)) (each proc (cdr l)))))
                        (define (f l) (call/cc (lambda (k) (each (lambda (x) (if (negative? x) (k x))) l) 0)))""");
        escapes("""
                        (define (vector-for-each-rev proc v)
                          ((letrec* ((lp (lambda (i) (if (>= i 0) (begin (proc (vector-ref v i)) (lp (- i 1)))))))
                             lp)
                           (- (vector-length v) 1)))
                        (define (dig v) (call/cc (lambda (quit) (vector-for-each-rev (lambda (x) (if (= x 0) (quit #f))) v))))""");
        // k passed along from inside a called lambda to a known procedure that only calls it.
        escapes("(define (leave k x) (k x)) (call/cc (lambda (k) (define (h x) (leave k x)) (+ 1 (h 2))))");
    }

    @Test
    public void keepsTheCaptureWhenALambdaThatUsesKMayOutliveTheCall() {
        // The lambda is stored, returned, or reachable from a stored or returned lambda.
        captures("(define saved #f) (call/cc (lambda (k) (define (h) (k 1)) (set! saved h) 0))");
        captures("(call/cc (lambda (k) (define (h) (k 1)) h))");
        captures("(call/cc (lambda (k) ((lambda (h) (list h)) (lambda () (k 1)))))");
        captures("(call/cc (lambda (k) (define (h) (k 1)) (lambda () (h))))");
        captures("(call/cc (lambda (k) (define (h) (k 1)) (define (g) (h)) g))");
        captures("""
                        (define saved #f)
                        (call/cc (lambda (k)
                                   ((letrec* ((loop (lambda (i) (if (< i 3) (begin (set! saved (lambda () (k i))) (loop (+ i 1)))))))
                                      loop)
                                    0)))""");
        captures("(call/cc (lambda (k) ((letrec* ((loop (lambda (i) (if (< i 3) (loop (+ i 1)) (lambda () (k i)))))) loop) 0)))");
        // A loop that is itself a value: passed on.
        captures("(call/cc (lambda (k) ((letrec* ((loop (lambda (i) (begin (if (= i 3) (k i)) (loop (+ i 1)))))) (g loop)) 0)))");
        // Passed to map, for-each, apply, or a procedure the unit doesn't know.
        captures("(define (f l) (call/cc (lambda (k) (map (lambda (x) (if x (k x))) l))))");
        captures("(define (f l) (call/cc (lambda (k) (define (h x) (if x (k x))) (for-each h l))))");
        captures("(call/cc (lambda (k) (apply (lambda (x) (k x)) '(1))))");
        captures("(call/cc (lambda (k) (g (lambda () (k 1)))))");
        captures("(call/cc (lambda (k) (define (h) (k 1)) (g h)))");
        // Used inside another call/cc's receiver whose own k escapes (j is returned): that call/cc
        // captures in full, and so does this one (M9-05: a receiver counts as only called when its
        // k is escape-only, NestedEscapeTest).
        captures("(call/cc (lambda (k) (define (h) (k 1)) (call/cc (lambda (j) (h) j))))");
        // A helper that is assigned, or a known procedure whose parameter lets it escape.
        captures("(call/cc (lambda (k) (define (h) (k 1)) (set! h (lambda () 2)) (h)))");
        captures("(call/cc (lambda (k) ((lambda (h) (set! h h) (h)) (lambda () (k 1)))))");
        captures("(define saved #f) (define (keep f) (set! saved f)) (call/cc (lambda (k) (keep (lambda () (k 1)))))");
        captures("(define (twice f) (lambda () (f) (f))) (call/cc (lambda (k) ((twice (lambda () (k 1))))))");
        captures("(define (f g) (set! g 1) g) (call/cc (lambda (k) (f (lambda () (k 1)))))");
        captures("(call/cc (lambda (k) ((lambda (h) (h 1)) (lambda (j) (vector j k)))))");
        // In the interaction environment a global procedure is unknown, even one that only calls.
        assertEquals(false, show("(define (each proc l) (if (pair? l) (begin (proc (car l)) (each proc (cdr l))))) (call/cc (lambda (k) (each (lambda (x) (k x)) '(1))))", false).contains("%call/ec"));
    }

    @Test
    public void globalsAreKnownOnlyInClosedUnits() {
        // In the interaction environment a later input may redefine f, so k stays a full capture.
        String program = "(define (f k) (k 1)) (call/cc (lambda (k) (f k)))";
        assertEquals(true, show(program, true).contains("%call/ec"));
        assertEquals(false, show(program, false).contains("%call/ec"));
    }

    // ------------------------------------------------------------------------------------------
    // Semantics: the same with an escape as with a capture

    @Test
    public void escapesAndReturns() {
        check("(3 6 -1 84 7)", """
                        (define (find-neg l k) (if (null? l) 0 (if (< (car l) 0) (k (car l)) (+ 1 (find-neg (cdr l) k)))))
                        (define (f k) (* 2 (g k)))
                        (define (g k) (+ 1 (k 42)))
                        (write (list (+ 1 (call/cc (lambda (k) 2)))
                                     (+ 1 (call/cc (lambda (k) (+ 10 (k 5)))))
                                     (call/cc (lambda (k) (find-neg '(1 2 3 -1 5) k)))
                                     (* 2 (call/cc (lambda (k) (f k))))
                                     (call/cc (lambda (k) (call/cc (lambda (j) (k 7)))))))""");
    }

    @Test
    public void passesMultipleValues() {
        check("((1 2) () (3 4) 5)", """
                        (define (two k) (k 3 4))
                        (write (list (call-with-values (lambda () (call/cc (lambda (k) (k 1 2)))) list)
                                     (call-with-values (lambda () (call/cc (lambda (k) (k)))) list)
                                     (call-with-values (lambda () (call/cc (lambda (k) (+ 1 (two k))))) list)
                                     (call/cc (lambda (k) (values 5)))))""");
    }

    @Test
    public void benchmarkShapes() {
        check("(5 21)", """
                        (define (ctak x y z) (call/cc (lambda (k) (ctak-aux k x y z))))
                        (define (ctak-aux k x y z)
                          (if (not (< y x))
                              (k z)
                              (call/cc
                               (lambda (k)
                                 (ctak-aux k
                                           (call/cc (lambda (k) (ctak-aux k (- x 1) y z)))
                                           (call/cc (lambda (k) (ctak-aux k (- y 1) z x)))
                                           (call/cc (lambda (k) (ctak-aux k (- z 1) x y))))))))
                        (define (addc x y k) (if (zero? y) (k x) (addc (+ x 1) (- y 1) k)))
                        (define (fibc x c)
                          (if (zero? x) (c 0)
                              (if (zero? (- x 1)) (c 1)
                                  (addc (call/cc (lambda (c) (fibc (- x 1) c))) (call/cc (lambda (c) (fibc (- x 2) c))) c))))
                        (write (list (ctak 12 8 4) (fibc 8 (lambda (n) n))))""");
    }

    @Test
    public void escapesFromLambdasThatAreOnlyCalled() {
        check("(5 -2 odd 3 #f 2 #t 0 7)", """
                        (define (first-neg l)
                          (call/cc (lambda (k) (let loop ((l l)) (if (pair? l) (begin (if (negative? (car l)) (k (car l))) (loop (cdr l))) 0)))))
                        (define (vector-for-each-rev proc v)
                          (let lp ((i (- (vector-length v) 1)))
                            (cond ((>= i 0) (proc (vector-ref v i)) (lp (- i 1))))))
                        (define (dig v) (call/cc (lambda (quit) (vector-for-each-rev (lambda (x) (if (= x 0) (quit #f))) v) #t)))
                        (define (sorted? v)
                          (call/cc (lambda (return)
                                     (do ((i 1 (+ i 1))) ((= i (vector-length v)) #t)
                                       (unless (<= (vector-ref v (- i 1)) (vector-ref v i)) (return #f))))))
                        (define (leave k x) (k x))
                        (write (list (call/cc (lambda (return) (do ((i 0 (+ i 1))) ((= i 10) #f) (if (= i 5) (return i)))))
                                     (first-neg '(1 2 -2 3))
                                     (call/cc (lambda (k)
                                                (letrec ((even (lambda (n) (if (= n 0) (k 'even) (odd (- n 1)))))
                                                         (odd (lambda (n) (if (= n 0) (k 'odd) (even (- n 1))))))
                                                  (+ 1 (even 7)))))
                                     (call/cc (lambda (k) (define (outer x) (define (inner y) (k y)) (+ 1 (inner x))) (* 2 (outer 3))))
                                     (dig (vector 1 0 2))
                                     (call/cc (lambda (k) (define (h x) (leave k x)) (+ 1 (h 2))))
                                     (sorted? (vector 1 2 3))
                                     (first-neg '(1 2))
                                     (+ 1 (call/cc (lambda (k) (let ((h (lambda (x) (k x)))) (+ 10 (h 6))))))))""");
    }

    @Test
    public void reentryIntoALoopThatUsesKThenEscape() {
        // A full continuation captured inside a loop that refers to k, re-entered after the
        // call/cc returned: the loop's k still escapes to its (copied) call/cc.
        check("(3 13 23)", """
                        (define saved #f)
                        (define results '())
                        (define v
                          (call/cc (lambda (k)
                                     (do ((i 0 (+ i 1))) (#f)
                                       (when (= i 3)
                                         (k (+ (call/cc (lambda (c) (set! saved c) 0)) i)))))))
                        (set! results (cons v results))
                        (if (< (length results) 3) (saved (* 10 (length results))))
                        (write (reverse results))""");
    }

    @Test
    public void dynamicWindAroundTheCallStillRuns() {
        check("(in out 1 in out 2)", """
                        (define trail '())
                        (define (note x) (set! trail (cons x trail)))
                        (define (leave k) (k 1))
                        (define a (dynamic-wind (lambda () (note 'in)) (lambda () (call/cc (lambda (k) (leave k)))) (lambda () (note 'out))))
                        (note a)
                        (define b (dynamic-wind (lambda () (note 'in)) (lambda () (call/cc (lambda (k) (+ 1 (k 2))))) (lambda () (note 'out))))
                        (note b)
                        (write (reverse trail))""");
    }

    /**
     * Invoking k in another dynamic environment than call/cc's (only internal code can make one
     * without a closure over k) rewinds first: the after-thunk runs and the environment is restored.
     */
    @Test
    public void escapeRewindsTheDynamicEnvironment() {
        String program = """
                        (define trail '())
                        (define (note x) (set! trail (cons x trail)))
                        (define before (%current-dynamic-env))
                        (define result
                          ((lambda ()
                             (define (wound env)
                               (%make-dynamic-env (cons (cons (lambda () (note 'in)) (cons (lambda () (note 'out)) env)) (%dynamic-env-winders env))
                                                  (%dynamic-env-parameterization env)
                                                  (%dynamic-env-handlers env)))
                             (define (leave-inside-winder k)
                               (%set-dynamic-env! (wound (%current-dynamic-env)))
                               (k 'escaped))
                             (call/cc (lambda (k) (leave-inside-winder k))))))
                        (write (list result trail (eq? before (%current-dynamic-env))))""";
        assertEquals(true, show(program, false).contains("%call/ec"));
        assertEquals("(escaped (out) #t)", run(program));
    }

    @Test
    public void exceptionHandlersAndGuard() {
        // The handler in effect around call/cc is the one in effect after the escape.
        check("(handled (escaped 11) caught (guarded 5))", """
                        (define (g k) (k (list 'escaped (+ 1 (raise-continuable 'oops)))))
                        (define (h k) (k 'escaped))
                        (define (r k) (raise 'boom) (k 'not-reached))
                        (define (s k) (+ 1 (k (list 'guarded 5))))
                        (write
                         (list
                          (with-exception-handler
                           (lambda (e) 'handled)
                           (lambda () (call/cc (lambda (k) (h k))) (raise-continuable 'x)))
                          ;; No clause matches: guard reinstates the raise's frames (the escape frame among
                          ;; them) and re-raises; the outer handler's value comes back, then k escapes.
                          (with-exception-handler
                           (lambda (e) 10)
                           (lambda () (guard (e ((string? e) 'string)) (call/cc (lambda (k) (g k))))))
                          ;; A raise inside the receiver unwinds to a guard outside call/cc.
                          (guard (e ((eq? e 'boom) 'caught)) (call/cc (lambda (k) (r k))))
                          (guard (e (#t 'wrong)) (call/cc (lambda (k) (s k))))))""");
    }

    @Test
    public void reentryIntoTheReceiverThenEscape() {
        // A full continuation captured inside the escape's extent, re-entered after the call/cc
        // returned: k still escapes to the (copied) continuation of its call/cc.
        check("(got 1)(got 2)(got 3)", """
                        (define saved #f)
                        (define n 0)
                        (define (f k) (call/cc (lambda (c) (set! saved c))) (set! n (+ n 1)) (k n) 'not-reached)
                        (write (list 'got (call/cc (lambda (k) (f k)))))
                        (if (< n 3) (saved #f))""");
        // The same, with the frames in between non-tail.
        check("(11 12 13)", """
                        (define saved #f)
                        (define n 0)
                        (define results '())
                        (define (f k) (+ 100 (g k)))
                        (define (g k) (call/cc (lambda (c) (set! saved c))) (set! n (+ n 1)) (+ 1000 (k (+ 10 n))))
                        (define v (call/cc (lambda (k) (f k))))
                        (set! results (cons v results))
                        (if (< n 3) (saved #f))
                        (write (reverse results))""");
    }

    @Test
    public void deepEscapeAcrossSpills() {
        // Past the spill depth the escape frame is on the heap: the escape finds it there. (Deeper
        // than any Java stack the tests run with, so it spills: plan.md M8-01.)
        check("(escaped 100000)", """
                        (define (down i k) (if (= i 100000) (k (list 'escaped i)) (+ 1 (down (+ i 1) k))))
                        (write (call/cc (lambda (k) (down 0 k))))""");
    }

    @Test
    public void tailCallCCInsideTheReceiverRunsInConstantSpace() {
        // call/cc in tail position of its own receiver's extent reuses that escape frame.
        // c's non-tail call of id lets torture mode flatten the escape frame into a marker at the
        // root prompt each time: the next tail call/cc and the escape then use that marker.
        check("((done 100000) 100000 (done 20000))", """
                        (define (a i) (if (< i 100000) (call/cc (lambda (k) (a (+ i 1)))) (list 'done i)))
                        (define (b i k) (if (< i 100000) (call/cc (lambda (j) (b (+ i 1) j))) (k i)))
                        (define (id x) x)
                        (define (c i k) (if (< (id i) 20000) (call/cc (lambda (j) (c (+ i 1) j))) (k (list 'done i))))
                        (write (list (a 0) (call/cc (lambda (k) (b 0 k))) (call/cc (lambda (k) (c 0 k)))))""");
    }
}
