package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.Test;

/**
 * M5-03: the standard libraries come from a JVM-wide image after the second context (design.md
 * D13). Every context, whether it instantiated the libraries or restored them, behaves the same.
 */
public class PreludeImageTest {

    private static final String SCHEME = "scheme";
    /** More than the contexts it takes to build the image, so some restore from it. */
    private static final int CONTEXTS = 4;

    private static String output(String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            context.eval(SCHEME, program);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void sameInEveryContext(String expected, String program) {
        for (int i = 0; i < CONTEXTS; i++) {
            assertEquals("context " + i, expected, output(program));
        }
    }

    @Test
    public void preludeMacros() {
        sameInEveryContext("(2 1) 3 caught: boom 10 #t (1 . 2) one 6 done 7", """
                        (define-record-type point (make-point x y) point? (x point-x) (y point-y))
                        (define p (make-parameter 1))
                        (define f (case-lambda ((a) a) ((a b) (+ a b))))
                        (define-values (q r) (floor/ 7 2))
                        (let-values (((a b) (values 1 2))) (display (list b a)))
                        (display " ") (display (f 1 2))
                        (display " ") (display (guard (e ((string? e) (string-append "caught: " e))) (raise "boom")))
                        (display " ") (display (parameterize ((p 10)) (p)))
                        (display " ") (display (point? (make-point 1 2)))
                        (display " ") (display (cons (point-x (make-point 1 2)) (point-y (make-point 1 2))))
                        (display " ") (display (case 1 ((1) 'one) (else 'other)))
                        (display " ") (display (do ((i 0 (+ i 1)) (s 0 (+ s i))) ((= i 4) s)))
                        (display " ") (display (force (delay 'done)))
                        (display " ") (display (+ (* q 2) r))
                        """);
    }

    @Test
    public void preludeMacrosAreHygienic() {
        sameInEveryContext("ok (1 2)", """
                        (display (let ((if list) (begin list)) (when #t 'ok)))
                        (display " ")
                        (display (let ((list vector)) (let loop ((i 0) (acc '())) (if (= i 2) (reverse acc) (loop (+ i 1) (cons (+ i 1) acc))))))
                        """);
    }

    @Test
    public void programsAndEnvironments() {
        sameInEveryContext("3 1 #t", """
                        (import (scheme base) (scheme write) (scheme eval))
                        (display (eval '(let-values (((a b) (values 1 2))) (+ a b)) (environment '(scheme base))))
                        (display " ")
                        (display (eval '(car '(1 2)) (environment '(only (scheme base) car quote))))
                        (display " ")
                        (display (guard (e (#t (error-object? e))) (eval '(cdr 1) (environment '(only (scheme base) car)))))
                        """);
    }

    @Test
    public void redefinitionsStayInTheirContext() {
        for (int i = 0; i < CONTEXTS; i++) {
            assertEquals("mine", output("(define (map f l) 'mine) (display (map car '((1))))"));
            assertEquals("(1)", output("(display (map car '((1))))"));
        }
    }

    @Test
    public void errorsThroughPreludeProceduresKeepLocations() {
        // The prelude's own frames are internal, so the trace leaves them out; the frames around
        // them (here: bad, called by map) keep their locations (design.md D13).
        for (int i = 0; i < CONTEXTS; i++) {
            try (Context context = Context.create(SCHEME)) {
                Source source = Source.newBuilder(SCHEME, "(define (bad x) (+ (vector-ref x 0) 1))\n(map bad '(1))", "uses-map.scm").buildLiteral();
                PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval(source));
                List<String> locations = new ArrayList<>();
                for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
                    if (frame.isGuestFrame() && frame.getSourceLocation() != null) {
                        locations.add(frame.getSourceLocation().getSource().getName() + ":" + frame.getSourceLocation().getStartLine());
                    }
                }
                assertTrue(locations.toString(), locations.contains("uses-map.scm:1"));
                assertTrue(locations.toString(), locations.contains("uses-map.scm:2"));
            }
        }
    }
}
