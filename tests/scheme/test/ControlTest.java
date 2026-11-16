package scheme.test;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.junit.Test;

/**
 * M4-05: booleans (R7RS §6.3), control features (§6.10) and exceptions (§6.11). Results are
 * compared as written, against the written expected datum. Under {@code mx tests torture} these run
 * with a capture and resume at every non-tail call.
 */
public class ControlTest {

    private static final String SCHEME = "scheme";

    private static String run(String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            context.eval(SCHEME, program);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void check(String expected, String expr) {
        String out = run("(write '" + expected + ") (display \"|\") (write " + expr + ")");
        String[] parts = out.split("\\|", 2);
        assertEquals(expr, parts[0], parts[1]);
    }

    /** expr raises an error object, which a guard catches. */
    private static void checkError(String expr) {
        check("error", "(guard (e ((error-object? e) 'error)) " + expr + ")");
    }

    @Test
    public void booleans() {
        check("(#t #t #f #f #f)", "(list (boolean? #f) (boolean? #t) (boolean? 0) (boolean? '()) (boolean? 'nil))");
        check("(#t #t #f)", "(list (boolean=? #t #t) (boolean=? #f #f) (boolean=? #t #f))");
        check("(#t #f #f #t)", "(list (boolean=? #f #f #f) (boolean=? #t #t #f) (boolean=? #f #t #t) (boolean=? #t #t #t #t))");
        checkError("(boolean=? 1 1)");
        checkError("(boolean=? #t #t 'x)");
        checkError("(boolean=? #t)");
    }

    @Test
    public void procedurePredicate() {
        check("(#t #f #t #f #t)", "(list (procedure? car) (procedure? 'car) (procedure? (lambda (x) x)) (procedure? '(lambda (x) x)) (procedure? procedure?))");
        // Continuations, parameters, prelude procedures and case-lambda procedures are procedures.
        check("(#t #t #t #t)", "(list (call/cc procedure?) (procedure? (make-parameter 1)) (procedure? map) (procedure? (case-lambda ((x) x) ((x y) y))))");
        check("(#f #f #f)", "(list (procedure? (vector)) (procedure? \"car\") (procedure? 1))");
    }

    @Test
    public void applyArguments() {
        check("(7 7 (1 2 3 4) ())", "(list (apply + (list 3 4)) (apply + 3 4 (list)) (apply list 1 2 '(3 4)) (apply list '()))");
        check("(1 2)", "(apply apply list '((1 2)))");
        checkError("(apply +)");
        checkError("(apply + 3)");
        checkError("(apply + 3 4)");
        checkError("(apply + '(2 3 . 4))");
        // apply in tail position runs in constant space.
        check("100000", "(let loop ((i 0)) (if (< i 100000) (apply loop (list (+ i 1))) i))");
    }

    @Test
    public void mapAndForEachOverSeveralLists() {
        check("(111 222)", "(map (lambda (a b c) (+ a b c)) '(1 2) '(10 20 30) '(100 200))");
        check("(10 200 3000 40 500 6000)", "(let ((ls1 (list 10 100 1000)) (ls2 (list 1 2 3 4 5 6))) (set-cdr! (cddr ls1) ls1) (map * ls1 ls2))");
        check("((1 x) (2 y))", "(let ((out '())) (for-each (lambda (a b) (set! out (cons (list a b) out))) '(1 2 3) '(x y)) (reverse out))");
        check("()", "(map + '() '(1 2))");
        // Re-entering map's procedure doesn't change a result map has already returned.
        check("((1 2 3) (1 10 3) (1 20 3))", """
                        (let ((k #f) (results '()))
                          (let ((r (map (lambda (x) (call/cc (lambda (c) (if (= x 2) (set! k c)) x))) '(1 2 3))))
                            (set! results (cons r results))
                            (if (< (length results) 3) (k (* 10 (length results))))
                            (reverse results)))""");
    }

    @Test
    public void valuesEdgeCases() {
        check("(() () (1) (1 2 3))", "(list (call-with-values values list) (call-with-values (lambda () (values)) list) " +
                        "(call-with-values (lambda () 1) list) (call-with-values (lambda () (values 1 2 3)) list))");
        check("-1", "(call-with-values * -)");
        check("5", "(call-with-values (lambda () (values 4 5)) (lambda (a b) b))");
        // Values pass through dynamic-wind, parameterize, with-exception-handler and guard.
        check("((1 2 3) () (6 1) (1 2) () (1 2))", """
                        (let ((p (make-parameter 10 (lambda (x) (* x 2)))))
                          (list (call-with-values (lambda () (dynamic-wind (lambda () #f) (lambda () (values 1 2 3)) (lambda () #f))) list)
                                (call-with-values (lambda () (dynamic-wind (lambda () #f) values (lambda () #f))) list)
                                (call-with-values (lambda () (parameterize ((p 3)) (values (p) 1))) list)
                                (call-with-values (lambda () (with-exception-handler (lambda (e) 0) (lambda () (values 1 2)))) list)
                                (call-with-values (lambda () (guard (e (#t 0)) (values))) list)
                                (call-with-values (lambda () (guard (e (#t (values 1 2))) (raise 1))) list)))""");
        // A continuation accepts as many values as its context does.
        check("(1 2)", "(call-with-values (lambda () (call/cc (lambda (k) (k 1 2)))) list)");
        check("()", "(call-with-values (lambda () (call/cc (lambda (k) (k)))) list)");
    }

    @Test
    public void dynamicWindReentry() {
        // Re-entry runs the before-thunks outermost first, and the after-thunks innermost first.
        check("(in1 in2 body out2 out1 in1 in2 body out2 out1 in1 in2 body out2 out1)", """
                        (let ((trail '()) (k #f) (n 0))
                          (define (note x) (set! trail (cons x trail)))
                          (dynamic-wind
                            (lambda () (note 'in1))
                            (lambda ()
                              (dynamic-wind
                                (lambda () (note 'in2))
                                (lambda () (call/cc (lambda (c) (set! k c))) (note 'body))
                                (lambda () (note 'out2))))
                            (lambda () (note 'out1)))
                          (set! n (+ n 1))
                          (if (< n 3) (k #f))
                          (reverse trail))""");
        // Jumping between two sibling extents leaves one and enters the other.
        check("(a-in a-out b-in b-out a-in a-out done)", """
                        (let ((trail '()) (ka #f) (first #t))
                          (define (note x) (set! trail (cons x trail)))
                          (dynamic-wind (lambda () (note 'a-in))
                                        (lambda () (call/cc (lambda (c) (set! ka c))))
                                        (lambda () (note 'a-out)))
                          (if first
                              (dynamic-wind (lambda () (note 'b-in))
                                            (lambda () (set! first #f) (ka #f))
                                            (lambda () (note 'b-out))))
                          (reverse (cons 'done trail)))""");
        // An escape from an after-thunk abandons the jump that ran it and starts a new one from the
        // after-thunk's own dynamic environment.
        check("(inner-out escaped outer-out)", """
                        (let ((trail '()))
                          (define (note x) (set! trail (cons x trail)))
                          (call/cc (lambda (top)
                            (dynamic-wind
                              (lambda () #f)
                              (lambda ()
                                (call/cc (lambda (escape)
                                  (dynamic-wind
                                    (lambda () #f)
                                    (lambda () (top 'jump))
                                    (lambda () (note 'inner-out) (escape #f)))))
                                (note 'escaped))
                              (lambda () (note 'outer-out)))))
                          (reverse trail))""");
    }

    @Test
    public void handlers() {
        // A handler runs with the outer handlers installed, so a raise inside it goes outward.
        check("(outer (inner x))", "(guard (e (#t (list 'outer e))) (with-exception-handler (lambda (e) (raise (list 'inner e))) (lambda () (raise 'x))))");
        check("11", "(with-exception-handler (lambda (e) 10) (lambda () (+ 1 (raise-continuable 'oops))))");
        check("(1 2)", "(call-with-values (lambda () (with-exception-handler (lambda (e) (values 1 2)) (lambda () (raise-continuable 'c)))) list)");
        // The handler is uninstalled after the thunk returns, and after an escape.
        check("(outer outer)", """
                        (let ((seen '()))
                          (with-exception-handler
                            (lambda (e) (set! seen (cons 'outer seen)) 0)
                            (lambda ()
                              (with-exception-handler (lambda (e) 'inner) (lambda () 1))
                              (raise-continuable 'a)
                              (call/cc (lambda (k) (with-exception-handler (lambda (e) 'inner) (lambda () (k 1)))))
                              (raise-continuable 'b)))
                          seen)""");
        // A handler returning from raise: a secondary exception, raised in the handler's dynamic
        // environment (so the outer guard sees it, not the handler again).
        check("(#t 1)", """
                        (let ((calls 0))
                          (guard (e (#t (list (error-object? e) calls)))
                            (with-exception-handler
                              (lambda (e) (set! calls (+ calls 1)) 'ignored)
                              (lambda () (raise 'oops)))))""");
    }

    @Test
    public void errorObjects() {
        check("(#t \"BOOM!\" (1 2 3))", "(guard (e ((error-object? e) (list (error-object? e) (error-object-message e) (error-object-irritants e)))) (error \"BOOM!\" 1 2 3))");
        check("()", "(guard (e ((error-object? e) (error-object-irritants e))) (error \"msg\"))");
        check("(#f #f #f)", "(guard (e (#t (list (error-object? e) (file-error? e) (read-error? e)))) (raise \"not an error object\"))");
        check("(#t #f #f)", "(guard (e (#t (list (error-object? e) (file-error? e) (read-error? e)))) (vector-ref (vector) 0))");
        check("(#f #f)", "(guard (e (#t (list (file-error? e) (read-error? e)))) (error \"BOOM!\"))");
        checkError("(error-object-message 'x)");
        checkError("(error-object-irritants 'x)");
        // The guard with no matching clause re-raises; an outer guard sees the same object.
        check("#t", "(let ((obj (list 'x))) (eq? obj (guard (e (#t e)) (guard (e ((string? e) 'no)) (raise obj)))))");
    }
}
