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
        // Past the spill depth the escape frame is on the heap: the escape finds it there.
        check("(escaped 3000)", """
                        (define (down i k) (if (= i 3000) (k (list 'escaped i)) (+ 1 (down (+ i 1) k))))
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
