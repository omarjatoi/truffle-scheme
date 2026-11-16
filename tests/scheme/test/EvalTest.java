package scheme.test;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.junit.Test;

/**
 * M4-07: eval and environments (R7RS §6.12, design.md D5, D6, D7). {@code eval} compiles in the
 * given environment and tail-calls the result; errors in the evaluated code, syntax errors
 * included, are raised like any other.
 */
public class EvalTest {

    private static final String SCHEME = "scheme";

    private static String run(String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            context.eval(SCHEME, program);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void check(String expected, String expr) {
        assertEquals(expr, expected, run("(write " + expr + ")"));
    }

    @Test
    public void specExamples() {
        check("21", "(eval '(* 7 3) (environment '(scheme base)))");
        check("21", "(eval '(* 7 3) (scheme-report-environment 5))");
        check("20", "(let ((f (eval '(lambda (f x) (f x x)) (null-environment 5)))) (f + 10))");
        check("1024.0", "(eval '(+ (expt 2 10) (inexact (sin 0))) (environment '(scheme base) '(scheme inexact)))");
    }

    @Test
    public void environmentImportSets() {
        check("(1 2)", "(eval '(list 1 2) (environment '(only (scheme base) list)))");
        check("3", "(eval '(b:+ 1 2) (environment '(prefix (scheme base) b:)))");
        // 'a is (quote a): quote is a binding like any other (design.md D15), so it is imported too.
        check("(a . b)", "(eval '(kons 'a 'b) (environment '(rename (only (scheme base) cons quote) (cons kons))))");
        // Macros from the imported libraries work, hygienically.
        check("6", "(eval '(let loop ((i 0) (acc 0)) (if (> i 3) acc (loop (+ i 1) (+ acc i)))) (environment '(scheme base)))");
        // An environment is a value of its own type.
        check("#t", "(eq? (environment '(scheme base)) (environment '(scheme base)))");
        check("(#f #f)", "(let ((e (environment '(scheme base)))) (list (pair? e) (symbol? e)))");
    }

    @Test
    public void nullEnvironmentHasOnlySyntax() {
        check("ok", "(eval '(if #t 'ok 'no) (null-environment 5))");
        check("3", "(eval '(let ((x 1) (y 2)) (cond ((= x 1) 3) (else 4))) (scheme-report-environment 5))");
        check("unbound", "(guard (e (#t 'unbound)) (eval '(car '(1)) (null-environment 5)))");
    }

    @Test
    public void interactionEnvironmentIsTheProgramsAndPersists() {
        assertEquals("5 6", run("""
                        (eval '(define x 5) (interaction-environment))
                        (display x)
                        (define y 6)
                        (display " ")
                        (display (eval 'y (interaction-environment)))"""));
        // eval with one argument uses the interaction environment, as in chibi.
        assertEquals("7", run("(define z 7) (display (eval 'z))"));
        assertEquals("#t", run("(display (eq? (interaction-environment) (interaction-environment)))"));
        assertEquals("(1 . 2)", run("(eval '(define-syntax kons (syntax-rules () ((_ a b) (cons a b)))) (interaction-environment)) (write (eval '(kons 1 2) (interaction-environment)))"));
    }

    @Test
    public void environmentsAreImmutable() {
        check("caught", "(guard (e ((error-object? e) 'caught)) (eval '(define x 1) (environment '(scheme base))))");
        check("caught", "(guard (e ((error-object? e) 'caught)) (eval '(set! car 1) (environment '(scheme base))))");
    }

    @Test
    public void errorsAreRaised() {
        // A syntax error in the evaluated datum is an error object, not a parse error of the program.
        check("caught", "(guard (e ((error-object? e) 'caught)) (eval '(if) (environment '(scheme base))))");
        check("caught", "(guard (e ((error-object? e) 'caught)) (eval '(lambda) (interaction-environment)))");
        check("caught", "(guard (e ((error-object? e) 'caught)) (environment '(no such library)))");
        check("caught", "(guard (e ((error-object? e) 'caught)) (eval 1 'not-an-environment))");
        check("caught", "(guard (e ((error-object? e) 'caught)) (scheme-report-environment 4))");
        check("caught", "(guard (e ((error-object? e) 'caught)) (eval '(car 1) (environment '(scheme base))))");
        check("oops", "(guard (e ((symbol? e) e)) (eval '(raise 'oops) (environment '(scheme base))))");
    }

    @Test
    public void evalIsATailCall() {
        // eval's result is in tail position, so this loop runs in constant space (design.md D5).
        check("done", """
                        (let ()
                          (define env (interaction-environment))
                          (eval '(define (count-down n) (if (= n 0) 'done (eval (list 'count-down (- n 1)) (interaction-environment)))) env)
                          (eval '(count-down 20000) env))""");
    }

    @Test
    public void continuationsReenterEvaluatedCode() {
        assertEquals("(3 3 6)", run("""
                        (define k #f)
                        (define n 0)
                        (define r (eval '(+ 1 (call/cc (lambda (c) (set! k c) 0))) (interaction-environment)))
                        (set! n (+ n 1))
                        (if (< n 3) (k n))
                        (write (list n r (+ n r)))"""));
    }
}
