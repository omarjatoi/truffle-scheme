package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.Test;

/**
 * M5-09: a call to a primitive that only returns values runs in place, without a Truffle call. At
 * a non-tail call site the {@code Call} operation runs a node of its own for the primitive; in tail
 * position the primitive runs in the calling frame instead of going back to the caller's
 * trampoline. Errors are still raised where they were before (design.md D8). Under
 * {@code //tests:unit_torture} these run with a capture and resume at every non-tail call.
 */
public class PrimitivesInPlaceTest {

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

    /** The root names of the guest frames of the uncaught error the program ends with, innermost first. */
    private static List<String> frames(String program) {
        try (Context context = Context.create(SCHEME)) {
            PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval(SCHEME, program));
            assertTrue(e.getMessage(), e.isGuestException());
            List<String> names = new ArrayList<>();
            for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
                if (frame.isGuestFrame()) {
                    names.add(frame.getRootName());
                }
            }
            return names;
        }
    }

    @Test
    public void primitiveCallsAreNotTruffleCalls() {
        // %raise-uncaught is a primitive that throws an error no call site raises, so the guest stack
        // trace shows the frames that were live when the primitive ran.
        List<String> nonTail = frames("(define (g x) (+ 1 (%raise-uncaught x))) (g 'boom)");
        assertFalse(nonTail.toString(), nonTail.contains("%raise-uncaught"));
        assertTrue(nonTail.toString(), nonTail.contains("g"));
        // In tail position it runs in f's frame, not from the caller's trampoline after f returned.
        List<String> tail = frames("(define (f x) (%raise-uncaught x)) (f 'boom)");
        assertFalse(tail.toString(), tail.contains("%raise-uncaught"));
        assertTrue(tail.toString(), tail.contains("f"));
        // A self tail call that isn't one (the procedure is a primitive) runs in place too.
        List<String> loop = frames("(define (h x) (if (pair? x) (h (cdr x)) (h2 x))) (define (h2 x) (%raise-uncaught x)) (h '(1 2))");
        assertFalse(loop.toString(), loop.contains("%raise-uncaught"));
    }

    @Test
    public void errorsInTailPositionAreRaisedInTheCaller() {
        String f = "(define (f x) (car x))";
        check(f, "(caught \"car: not a pair: 1\")", "(guard (e ((error-object? e) (list 'caught (error-object-message e)))) (f 1))");
        // The handler runs in the frame of the call of f: a continuation captured there sees (+ 100 []).
        check(f, "101", "(+ 100 (call/cc (lambda (k) (with-exception-handler (lambda (e) (k 1)) (lambda () (f 1))))))");
        check(f, "(1 . 101)", "(cons 1 (+ 100 (call/cc (lambda (k) (with-exception-handler (lambda (e) (k 1)) (lambda () (+ 0 (f 1))))))))");
        // Arity errors too, in tail position and not.
        check("(define (t) (car)) (define (n) (+ 1 (car)))", "(\"car: expected 1 arguments, got 0\" \"car: expected 1 arguments, got 0\")",
                        "(list (guard (e (#t (error-object-message e))) (t)) (guard (e (#t (error-object-message e))) (n)))");
        // A primitive error in tail position deep in non-tail recursion (frames spilled to the heap).
        check("", "caught", "(guard (e (#t 'caught)) (let loop ((i 0)) (if (= i 100000) (vector-ref (vector) i) (+ 1 (loop (+ i 1))))))");
    }

    @Test
    public void callSitesSeeingSeveralProcedures() {
        // One tail site and one non-tail site, each called with primitives (more than the cache holds),
        // a primitive that transfers control, and closures, in an order that switches back and forth.
        String defs = "(define (tail f . xs) (apply-1 f xs)) (define (apply-1 f xs) (if (null? (cdr xs)) (f (car xs)) (f (car xs) (cadr xs))))" + "(define (non-tail f . xs) (list (apply-1 f xs)))";
        String calls = "(list (%s car '(1 2)) (%s cdr '(1 2)) (%s (lambda (x) (* x 2)) 21) (%s + 1 2) (%s - 5 3) (%s * 2 3)" +
                        " (%s vector-ref #(a b c) 2) (%s string-ref \"xyz\" 0) (%s call/cc (lambda (k) (k 'escaped)))" +
                        " (%s cons 1 2) (%s not #f) (%s (lambda (a b) (list b a)) 1 2) (%s car '(again)))";
        check(defs, "(1 (2) 42 3 2 6 c #\\x escaped (1 . 2) #t (2 1) again)", calls.replace("%s", "tail"));
        check(defs, "((1) ((2)) (42) (3) (2) (6) (c) (#\\x) (escaped) ((1 . 2)) (#t) ((2 1)) (again))", calls.replace("%s", "non-tail"));
        // A wrong arity at a site that has already run the primitive in place.
        check(defs, "(1 \"car: expected 1 arguments, got 2\")",
                        "(list (tail car '(1)) (guard (e (#t (error-object-message e))) (tail car '(1) 2)))");
    }

    @Test
    public void redefiningAGlobalFallsBackToTheNewProcedure() {
        // The sites below cache car's node while my-car holds car (a constant under the global's
        // assumption); each redefinition must reach them.
        try (Context context = Context.create(SCHEME)) {
            context.eval(SCHEME, "(define my-car car) (define (first x) (my-car x)) (define (first+1 x) (+ 1 (my-car x)))");
            for (int i = 0; i < 1000; i++) {
                assertEquals(1L, context.eval(SCHEME, "(first '(1 2))").asLong());
                assertEquals(2L, context.eval(SCHEME, "(first+1 '(1 2))").asLong());
            }
            context.eval(SCHEME, "(set! my-car cadr)");
            assertEquals(2L, context.eval(SCHEME, "(first '(1 2))").asLong());
            assertEquals(3L, context.eval(SCHEME, "(first+1 '(1 2))").asLong());
            context.eval(SCHEME, "(set! my-car length)");
            assertEquals(2L, context.eval(SCHEME, "(first '(1 2))").asLong());
            assertEquals(3L, context.eval(SCHEME, "(first+1 '(1 2))").asLong());
            context.eval(SCHEME, "(set! my-car (lambda (x) 10))");
            assertEquals(10L, context.eval(SCHEME, "(first '(1 2))").asLong());
            assertEquals(11L, context.eval(SCHEME, "(first+1 '(1 2))").asLong());
        }
    }

    @Test
    public void escapeOnlyContinuationsAroundPrimitivesInPlace() {
        // M5-04's escape procedures and %call/ec are not primitives, so tail calls to them still go to
        // the trampoline or the prompt's driver; a receiver whose tail call is a primitive runs it in
        // place and returns its value to the prompt.
        String find = "(define (find-neg lst) (call/cc (lambda (return) (for-each (lambda (x) (if (negative? x) (return x))) lst) (car lst))))";
        check(find, "(-2 1)", "(list (find-neg '(1 -2 3)) (find-neg '(1 2 3)))");
        check("", "(42 43 5)", "(list (call/cc (lambda (k) (+ 1 (k 42)))) (+ 1 (call/cc (lambda (k) (k 42)))) (call/cc (lambda (k) (car '(5)))))");
        check("", "caught", "(guard (e (#t 'caught)) (call/cc (lambda (k) (car k))))");
    }

    @Test
    public void primitivesThatTransferControlStillDoSo() {
        check("(define (f . xs) (apply list xs)) (define (g k) (k 'out))", "((1 2) out (1 2) 3)",
                        "(list (f 1 2) (call/cc (lambda (k) (g k))) (call-with-values (lambda () (values 1 2)) list) (eval '(+ 1 2) (environment '(scheme base))))");
    }
}
