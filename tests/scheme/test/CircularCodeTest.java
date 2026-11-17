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
 * Circular structure in code (R7RS §2.4: "It is an error for a program or library to include
 * circular references except in literals. In particular, it is an error for quasiquote (section
 * 4.2.8) to contain them."). The expander used to expand such code forever; it is a syntax error
 * (design.md D9), and circular literals still work. Each test has a timeout, so a hang fails it.
 */
public class CircularCodeTest {

    private static final String SCHEME = "scheme";

    private static final String PRELUDE = """
                    (import (scheme base) (scheme write) (scheme eval))
                    (define env (environment '(scheme base)))
                    ;; Whether eval of the datum raises; the condition's message, if it is an error object.
                    (define (eval-error datum)
                      (guard (e ((error-object? e) (error-object-message e)) (#t (list 'raised e)))
                        (eval datum env)
                        'no-error))
                    """;

    private static String run(String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).err(out).build()) {
            context.eval(SCHEME, PRELUDE + program);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static PolyglotException error(String program) {
        try (Context context = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).build()) {
            PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval(SCHEME, program));
            assertTrue(e.getMessage(), e.isGuestException());
            return e;
        }
    }

    /** M7-06's known bug: (eval '#0=(car #0#) env) expanded forever. */
    @Test(timeout = 60_000)
    public void evalOfACircularExpressionIsAnError() {
        // (car (car (car ...))): circular through a car.
        String out = run("""
                        (define e (list 'car 1))
                        (set-car! (cdr e) e)
                        (display (eval-error e))
                        """);
        assertTrue(out, out.contains("circular"));
        // (begin 1 2 1 2 ...): a circular body.
        out = run("""
                        (define body (list 1 2))
                        (set-cdr! (cdr body) body)
                        (display (eval-error (cons 'begin body)))
                        """);
        assertTrue(out, out.contains("circular"));
        // A circular argument list, and a circular lambda body.
        out = run("""
                        (define args (list 1 2))
                        (set-cdr! (cdr args) args)
                        (display (eval-error (cons '+ args)))
                        (newline)
                        (define lam (list 'lambda '() 1))
                        (set-cdr! (cddr lam) (cddr lam))
                        (display (eval-error (list lam)))
                        """);
        assertEquals(out, 2, out.split("circular", -1).length - 1);
    }

    @Test(timeout = 60_000)
    public void quasiquoteMayNotContainCycles() {
        // §2.4 names quasiquote: its template may not be circular, even under a quote.
        String out = run("""
                        (define t (list 1 2))
                        (set-cdr! (cdr t) t)
                        (display (eval-error (list 'quasiquote t)))
                        (newline)
                        (display (eval-error (list 'quasiquote (list 'quote t))))
                        """);
        assertEquals(out, 2, out.split("circular", -1).length - 1);
    }

    @Test(timeout = 60_000)
    public void circularLiteralsAreFine() {
        // Through eval: a quoted circular list, and a vector (self-evaluating) holding one.
        String out = run("""
                        (define x (list 1 2))
                        (set-cdr! (cdr x) x)
                        (write (eval (list 'quote x) env))
                        (write (vector-ref (eval (vector 'a x) env) 1))
                        (define shared (list '+ 1 2))
                        (write (eval (list '+ shared shared (list '- shared)) env))
                        """);
        assertEquals("#0=(1 2 . #0#)#0=(1 2 . #0#)3", out);
        // In program text, through datum labels.
        out = run("""
                        (write '#0=(a b . #0#))
                        (write (vector-ref #(1 #1=(c . #1#)) 1))
                        (write (let ((x '#2=(d . #2#))) (cadr x)))
                        """);
        assertEquals("#0=(a b . #0#)#0=(c . #0#)d", out);
    }

    @Test(timeout = 60_000)
    public void circularProgramTextIsASyntaxError() {
        PolyglotException e = error("(import (scheme base) (scheme write))\n#0=(display . #0#)");
        assertTrue(e.getMessage(), e.getMessage().contains("circular structure in code"));
        e = error("(import (scheme base) (scheme write))\n(display #0=(car #0#))");
        assertTrue(e.getMessage(), e.getMessage().contains("circular structure in code"));
        e = error("(import (scheme base) (scheme write))\n(display `(1 #0=(2 . #0#)))");
        assertTrue(e.getMessage(), e.getMessage().contains("circular structure in code"));
        // A syntax-rules template is walked as a template, so it may not be circular either.
        e = error("(import (scheme base) (scheme write))\n(define-syntax m (syntax-rules () ((_) '#0=(1 2 . #0#))))\n(display (m))");
        assertTrue(e.getMessage(), e.getMessage().contains("circular structure in code"));
    }
}
