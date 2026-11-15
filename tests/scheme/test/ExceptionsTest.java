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
 * M3-06: exceptions (R7RS §6.11, design.md D8), guard (§4.2.7, D4), parameters (§4.2.6) and records
 * (§5.5). Results are compared as written, against the written expected datum. Under
 * {@code mx tests torture} these run with a capture and resume at every non-tail call.
 */
public class ExceptionsTest {

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

    private static void check(String expected, String expr) {
        check("", expected, expr);
    }

    /** The program fails with an uncaught error; returns {output, message}. */
    private static String[] failure(String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval(SCHEME, program));
            assertTrue(e.getMessage(), e.isGuestException());
            return new String[]{out.toString(StandardCharsets.UTF_8), e.getMessage()};
        }
    }

    @Test
    public void guardExamples() {
        // §4.2.7
        check("42", "(guard (condition ((assq 'a condition) => cdr) ((assq 'b condition))) (raise (list (cons 'a 42))))");
        check("(b . 23)", "(guard (condition ((assq 'a condition) => cdr) ((assq 'b condition))) (raise (list (cons 'b 23))))");
        check("caught", "(guard (e (#t 'caught)) (raise 'oops))");
        check("other", "(guard (e ((string? e) 'string) (else 'other)) (raise 'oops))");
        // No clause matches: re-raised to the outer guard, in the raise's dynamic environment.
        check("(outer inner-unmatched)", "(guard (e (#t (list 'outer e))) (guard (e ((string? e) 'no)) (raise 'inner-unmatched)))");
        // raise-continuable through a guard with no matching clause returns to the raise.
        check("11", "(with-exception-handler (lambda (e) 10) (lambda () (guard (e ((string? e) 'no)) (+ 1 (raise-continuable 'c)))))");
        // The guard's value is the body's, values included.
        check("(1 2)", "(call-with-values (lambda () (guard (e (#t 0)) (values 1 2))) list)");
        // The clauses run after the after-thunks, in the guard's dynamic environment.
        assertEquals("before after (caught x)", run("""
                        (display (guard (e (#t (list 'caught e)))
                          (dynamic-wind (lambda () (display "before "))
                                        (lambda () (raise 'x))
                                        (lambda () (display "after ")))))"""));
    }

    @Test
    public void primitiveErrorsAreRaisedWhereThePrimitiveWasCalled() {
        // D8: a primitive's error reaches Scheme handlers as an error object.
        check("(#t \"car: not a pair: 1\" ())", "(guard (e ((error-object? e) (list #t (error-object-message e) (error-object-irritants e)))) (car 1))");
        check("handled", "(guard (e (#t 'handled)) (undefined-variable-here))");
        check("handled", "(guard (e (#t 'handled)) ((lambda (x) x)))");
        check("handled", "(guard (e (#t 'handled)) (1 2))");
        // The handler runs in the frame that called the primitive: raise-continuable semantics
        // aren't available for primitive errors, but a continuation captured in the handler sees
        // the pending (+ 100 []).
        check("101", "(+ 100 (call/cc (lambda (k) (with-exception-handler (lambda (e) (k 1)) (lambda () (car 1))))))");
        // Deep in non-tail recursion, frames have spilled to the heap and run as resumed segments:
        // an error there is still raised for the next frame out, and the guard catches it.
        check("(caught 100000)", "(guard (e ((error-object? e) (list 'caught (car (error-object-irritants e))))) " +
                        "(let loop ((i 0)) (if (= i 100000) (error \"deep\" i) (+ 1 (loop (+ i 1))))))");
        check("caught", "(guard (e (#t 'caught)) (let loop ((i 0)) (if (= i 100000) (car i) (+ 1 (loop (+ i 1))))))");
    }

    @Test
    public void section611Examples() {
        assertEquals("condition: an-error\nexception", run("""
                        (display (call-with-current-continuation
                          (lambda (k)
                            (with-exception-handler
                              (lambda (e) (display "condition: ") (write e) (newline) (k 'exception))
                              (lambda () (+ 1 (raise 'an-error)))))))"""));
        // The handler returns from a non-continuable raise: a secondary exception.
        String[] secondary = failure("""
                        (with-exception-handler
                          (lambda (e) (display "something went wrong\\n"))
                          (lambda () (+ 1 (raise 'an-error))))""");
        assertEquals("something went wrong\n", secondary[0]);
        assertTrue(secondary[1], secondary[1].contains("non-continuable"));
        assertEquals("should be a number65", run("""
                        (display (with-exception-handler
                          (lambda (con)
                            (cond ((string? con) (display con))
                                  (else (display "a warning has been issued")))
                            42)
                          (lambda () (+ (raise-continuable "should be a number") 23))))"""));
        String nullList = "(define (null-list? l) (cond ((pair? l) #f) ((null? l) #t) (else (error \"null-list?: argument out of domain\" l))))";
        check(nullList, "(#t \"null-list?: argument out of domain\" (5))",
                        "(guard (e ((error-object? e) (list (error-object? e) (error-object-message e) (error-object-irritants e)))) (null-list? 5))");
        check("(#f #f #f)", "(guard (e (#t (list (error-object? e) (read-error? e) (file-error? e)))) (raise 'x))");
    }

    @Test
    public void uncaughtRaises() {
        assertTrue(failure("(raise 'boom)")[1].contains("uncaught exception: boom"));
        assertTrue(failure("(error \"bad thing:\" 1 'two)")[1].contains("bad thing: 1 two"));
        assertTrue(failure("(car 1)")[1].contains("car: not a pair: 1"));
        // A guard whose clauses don't match lets the raise through.
        assertTrue(failure("(guard (e ((string? e) 'no)) (raise 'through))")[1].contains("uncaught exception: through"));
    }

    @Test
    public void parameterizeExamples() {
        // §4.2.6's radix, with a converter written in what exists now (number->string arrives in M4).
        String radix = """
                        (define radix (make-parameter 10 (lambda (x) (if (and (< 1 x) (< x 17)) x (error "invalid radix" x)))))
                        (define (f n) (list n 'base (radix)))
                        """;
        check(radix, "(12 base 10)", "(f 12)");
        check(radix, "(12 base 2)", "(parameterize ((radix 2)) (f 12))");
        check(radix, "(12 base 10)", "(begin (parameterize ((radix 2)) (f 12)) (f 12))");
        check(radix, "invalid", "(guard (e ((error-object? e) 'invalid)) (parameterize ((radix 0)) (f 12)))");
        // The converter is applied to the initial value too, and bindings nest and unwind on escape.
        check("(defined 20 30 20 10)", """
                        (let* ((p (make-parameter 10 (lambda (x) (* x 1))))
                               (seen '()))
                          (parameterize ((p 20))
                            (set! seen (cons (p) seen))
                            (parameterize ((p 30)) (set! seen (cons (p) seen)))
                            (call/cc (lambda (k) (parameterize ((p 40)) (k 0))))
                            (set! seen (cons (p) seen)))
                          (cons 'defined (reverse (cons (p) seen))))""");
        // Re-entering a continuation captured inside parameterize restores the binding: 2 inside,
        // 1 after, each time.
        check("(2 1 2 1)", """
                        (let* ((p (make-parameter 1)) (k #f) (out '()))
                          (parameterize ((p 2)) (call/cc (lambda (c) (set! k c))) (set! out (cons (p) out)))
                          (set! out (cons (p) out))
                          (if (< (length out) 3) (k #f))
                          (reverse out))""");
    }

    private static final String PARE = "(define-record-type <pare> (kons x y) pare? (x kar set-kar!) (y kdr))";

    @Test
    public void records() {
        // §5.5
        check(PARE, "#t", "(pare? (kons 1 2))");
        check(PARE, "#f", "(pare? (cons 1 2))");
        check(PARE, "1", "(kar (kons 1 2))");
        check(PARE, "2", "(kdr (kons 1 2))");
        check(PARE, "3", "(let ((k (kons 1 2))) (set-kar! k 3) (kar k))");
        // A constructor that sets some fields, in a different order; records in a body.
        check("(2 1 #t)", """
                        (let ()
                          (define-record-type point (make-point y x) point? (x point-x) (y point-y) (z point-z set-point-z!))
                          (let ((p (make-point 1 2)))
                            (set-point-z! p #t)
                            (list (point-x p) (point-y p) (point-z p))))""");
        // Distinct types; an accessor on the wrong type is an error.
        check(PARE + "(define-record-type <other> (other x) other? (x other-x))", "(#f wrong)",
                        "(list (pare? (other 1)) (guard (e ((error-object? e) 'wrong)) (kar (other 1))))");
    }
}
