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
 * M3-05: R7RS §4.2's derived expression types (§7.3's syntax-rules definitions, in the prelude)
 * and quasiquote (in the expander), checked against §4.2's examples.
 *
 * <p>Results are compared as written by {@code write}, against the written form of the expected
 * datum ({@code equal?} arrives in M4). Where an example calls a procedure that arrives in M4
 * ({@code zero?}, {@code abs}, {@code sqrt}, {@code exact-integer-sqrt}, {@code make-vector}),
 * the example uses an equivalent that exists now, and says so.
 */
public class DerivedSyntaxTest {

    private static final String SCHEME = "scheme";

    private static String run(String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            context.eval(SCHEME, program);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Asserts that {@code expr} (after {@code defs}) writes as the datum {@code expected} does. */
    private static void check(String defs, String expected, String expr) {
        String out = run(defs + "\n(write '" + expected + ") (display \"|\") (write " + expr + ")");
        String[] parts = out.split("\\|", 2);
        assertEquals(expr, parts[0], parts[1]);
    }

    private static void check(String expected, String expr) {
        check("", expected, expr);
    }

    @Test
    public void conditionals() {
        // §4.2.1
        check("greater", "(cond ((> 3 2) 'greater) ((< 3 2) 'less))");
        check("equal", "(cond ((> 3 3) 'greater) ((< 3 3) 'less) (else 'equal))");
        check("2", "(cond ((assv 'b '((a 1) (b 2))) => cadr) (else #f))");
        check("composite", "(case (* 2 3) ((2 3 5 7) 'prime) ((1 4 6 8 9) 'composite))");
        check("c", "(case (car '(c d)) ((a e i o u) 'vowel) ((w y) 'semivowel) (else => (lambda (x) x)))");
        check("#t", "(and (= 2 2) (> 2 1))");
        check("#f", "(and (= 2 2) (< 2 1))");
        check("(f g)", "(and 1 2 'c '(f g))");
        check("#t", "(and)");
        check("#t", "(or (= 2 2) (> 2 1))");
        check("#t", "(or (= 2 2) (< 2 1))");
        check("#f", "(or #f #f #f)");
        check("(b c)", "(or (memq 'b '(a b c)) (car '()))");
        assertEquals("12", run("(when (= 1 1.0) (display \"1\") (display \"2\"))"));
        assertEquals("", run("(unless (= 1 1.0) (display \"1\") (display \"2\"))"));
        // case with => on a matching clause, and no clause matching.
        check("(x)", "(case 'x ((x y) => list) (else 'no))");
        check("none", "(if (eq? (case 3 ((1) 'one)) 'one) 'one 'none)");
    }

    @Test
    public void bindingConstructs() {
        // §4.2.2
        check("6", "(let ((x 2) (y 3)) (* x y))");
        check("35", "(let ((x 2) (y 3)) (let ((x 7) (z (+ x y))) (* z x)))");
        check("70", "(let ((x 2) (y 3)) (let* ((x 7) (z (+ x y))) (* z x)))");
        // zero? arrives in M4: (= n 0) instead.
        check("#t", "(letrec ((even? (lambda (n) (if (= n 0) #t (odd? (- n 1))))) (odd? (lambda (n) (if (= n 0) #f (even? (- n 1)))))) (even? 88))");
        check("5", "(letrec* ((p (lambda (x) (+ 1 (q (- x 1))))) (q (lambda (y) (if (= y 0) 0 (+ 1 (p (- y 1)))))) (x (p 5)) (y x)) y)");
        // exact-integer-sqrt arrives in M4: (values 5 7) is (exact-integer-sqrt 32).
        check("35", "(let-values (((root rem) (values 5 7))) (* root rem))");
        check("(x y x y)", "(let ((a 'a) (b 'b) (x 'x) (y 'y)) (let*-values (((a b) (values x y)) ((x y) (values a b))) (list a b x y)))");
        check("(1 (2 3) 4)", "(let-values (((a . rest) (values 1 2 3)) (all (values 4))) (list a rest (car all)))");
        // §5.3.3, with (values 4 1) for (exact-integer-sqrt 17).
        check("(define-values (x y) (values 4 1))", "(4 1)", "(list x y)");
        check("3", "(let () (define-values (x y) (values 1 2)) (+ x y))");
        check("(1 (2 3))", "(let () (define-values (a . b) (values 1 2 3)) (list a b))");
        check("(1 2)", "(let () (define-values all (values 1 2)) all)");
        check("ok", "(let () (define-values () (values)) 'ok)");
    }

    @Test
    public void sequencingAndIteration() {
        // §4.2.3
        check("(define x 0)", "6", "(and (= x 0) (begin (set! x 5) (+ x 1)))");
        assertEquals("4 plus 1 equals 5", run("(begin (display \"4 plus 1 equals \") (display (+ 4 1)))"));
        // §4.2.4; make-vector arrives in M4, so the first example collects a list.
        check("(4 3 2 1 0)", "(do ((acc '() (cons i acc)) (i 0 (+ i 1))) ((= i 5) acc))");
        check("25", "(let ((x '(1 3 5 7 9))) (do ((x x (cdr x)) (sum 0 (+ sum (car x)))) ((null? x) sum)))");
        check("((6 1 3) (-5 -2))", """
                        (let loop ((numbers '(3 -2 1 6 -5)) (nonneg '()) (neg '()))
                          (cond ((null? numbers) (list nonneg neg))
                                ((>= (car numbers) 0) (loop (cdr numbers) (cons (car numbers) nonneg) neg))
                                ((< (car numbers) 0) (loop (cdr numbers) nonneg (cons (car numbers) neg)))))""");
    }

    @Test
    public void namedLetAndDoLoopInConstantSpace() {
        // Self tail calls compile to loops: 10M iterations, nothing left behind.
        check("10000000", "(let loop ((i 0)) (if (= i 10000000) i (loop (+ i 1))))");
        check("10000000", "(do ((i 0 (+ i 1))) ((= i 10000000) i))");
    }

    private static final String STREAMS = """
                    (define integers (letrec ((next (lambda (n) (delay (cons n (next (+ n 1))))))) (next 0)))
                    (define (head stream) (car (force stream)))
                    (define (tail stream) (cdr (force stream)))
                    (define (stream-filter p? s)
                      (delay-force
                        (if (null? (force s))
                            (delay '())
                            (let ((h (car (force s))) (t (cdr (force s))))
                              (if (p? h)
                                  (delay (cons h (stream-filter p? t)))
                                  (stream-filter p? t))))))
                    (define (odd? n) (if (= n 0) #f (if (= n 1) #t (odd? (- n 2)))))
                    """;

    @Test
    public void delayedEvaluation() {
        // §4.2.5
        check("3", "(force (delay (+ 1 2)))");
        check("(3 3)", "(let ((p (delay (+ 1 2)))) (list (force p) (force p)))");
        check(STREAMS, "2", "(head (tail (tail integers)))");
        check(STREAMS, "5", "(head (tail (tail (stream-filter odd? integers))))");
        String reentrant = """
                        (define count 0)
                        (define p (delay (begin (set! count (+ count 1)) (if (> count x) count (force p)))))
                        (define x 5)
                        """;
        check(reentrant, "6", "(force p)");
        check(reentrant, "6", "(begin (force p) (set! x 10) (force p))");
        check("(#t #t #f 5 5 7)", "(list (promise? (delay 1)) (promise? (make-promise 1)) (promise? 5) (force (make-promise 5)) (force (delay-force (make-promise 5))) (force 7))");
        // The space-safe implementation (D9): a long delay-force chain forces iteratively.
        check("(define (loop n) (delay-force (if (= n 0) (delay 'done) (loop (- n 1)))))", "done", "(force (loop 1000000))");
    }

    @Test
    public void quasiquote() {
        // §4.2.8; abs and sqrt arrive in M4, so the lists are mapped through lambdas.
        check("(list 3 4)", "`(list ,(+ 1 2) 4)");
        check("(list a (quote a))", "(let ((name 'a)) `(list ,name ',name))");
        check("(a 3 4 5 6 b)", "`(a ,(+ 1 2) ,@(map (lambda (x) (if (< x 0) (- x) x)) '(4 -5 6)) b)");
        check("((foo 7) . cons)", "`((foo ,(- 10 3)) ,@(cdr '(c)) . ,(car '(cons)))");
        check("#(10 5 2 4 3 8)", "`#(10 5 ,(+ 1 1) ,@(map (lambda (x) (- x 12 (- x 16) -3)) '(16 9)) 8)".replace("(- x 12 (- x 16) -3)", "(if (= x 16) 4 3)"));
        check("(list foo bar baz)", "(let ((foo '(foo bar)) (@baz 'baz)) `(list ,@foo , @baz))");
        check("(a (quasiquote (b (unquote (+ 1 2)) (unquote (foo 4 d)) e)) f)", "`(a `(b ,(+ 1 2) ,(foo ,(+ 1 3) d) e) f)");
        check("(a (quasiquote (b (unquote x) (unquote (quote y)) d)) e)", "(let ((name1 'x) (name2 'y)) `(a `(b ,,name1 ,',name2 d) e))");
        check("(list 3 4)", "(quasiquote (list (unquote (+ 1 2)) 4))");
        check("(quasiquote (list (unquote (+ 1 2)) 4))", "'(quasiquote (list (unquote (+ 1 2)) 4))");
        // A constant template is a literal; unquote-splicing at the end shares the spliced list.
        check("(1 2 (3))", "`(1 2 (3))");
        check("#t", "(let ((l '(2 3))) (eq? (cdr `(1 ,@l)) l))");
        check("x", "`,'x");
        // quasiquote, unquote and unquote-splicing are bindings: a local unquote is a variable.
        check("(1 (unquote x))", "(let ((unquote 0)) `(1 ,x))");
    }

    @Test
    public void quasiquoteWithSeveralOperands() {
        // M4-12: R7RS calls (unquote e ...) with other than one operand unpredictable; we give it
        // R6RS's meaning (§11.17), as Larceny's base tests expect: in a list, every operand's value
        // is inserted, or spliced.
        check("(foo foo foo)", "(let ((name 'foo)) `((unquote name name name)))");
        check("(foo foo foo)", "(let ((name '(foo))) `((unquote-splicing name name name)))");
        check("(a b)", "`(a (unquote) b)");
        check("(a 1 2 3 b)", "(let ((x '(1 2)) (y '(3))) `(a (unquote-splicing x y) b))");
        check("(foo (2 3 4 5) -9)", "(let ((x '(2 3)) (y '(4 5))) `(foo (unquote (append x y) (- 9))))");
        // At a deeper level they stay, and an unquote-splicing inside one splices into it.
        check("(quasiquote (foo (unquote (append x y) (sqrt 9))))", "(let ((q '((append x y) (sqrt 9)))) ``(foo ,,@q))");
        check("(quasiquote (a (unquote x y)))", "``(a (unquote x y))");
        try (Context context = Context.create(SCHEME)) {
            // Outside a list, unquote still takes one expression.
            PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval(SCHEME, "`(unquote 1 2)"));
            assertTrue(e.getMessage(), e.getMessage().contains("unquote takes one expression"));
        }
    }

    @Test
    public void caseLambda() {
        // §4.2.9
        String range = "(define range (case-lambda ((e) (range 0 e)) ((b e) (do ((r '() (cons e r)) (e (- e 1) (- e 1))) ((< e b) r)))))";
        check(range, "(0 1 2)", "(range 3)");
        check(range, "(3 4)", "(range 3 5)");
        String plus = "(define plus (case-lambda (() 0) ((x) x) ((x y) (+ x y)) ((x y z) (+ (+ x y) z)) (args (apply + args))))";
        check(plus, "(0 1 3 6 10)", "(list (plus) (plus 1) (plus 1 2) (plus 1 2 3) (plus 1 2 3 4))");
        check("(1 (2 3))", "((case-lambda ((a) a) ((a . rest) (list a rest))) 1 2 3)");
        try (Context context = Context.create(SCHEME)) {
            PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval(SCHEME, "((case-lambda ((a) a)))"));
            assertTrue(e.getMessage(), e.getMessage().contains("no clause matches"));
        }
    }

    @Test
    public void hygiene() {
        // The derived forms' own if, let and temporaries are immune to the use site's bindings.
        check("(2 1)", "(let ((if list) (temp 1) (x 2)) (or #f (list x temp)))");
        check("ok", "(let ((memv #f) (else #f)) (case 1 ((1) 'ok)))");
        check("3", "(let ((loop 3)) (do ((i 0 (+ i 1))) ((= i 2) loop)))");
    }

    /** The message of the error that evaluating {@code program} raises. */
    private static String error(String program) {
        try (Context context = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).build()) {
            PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval(SCHEME, program));
            assertTrue(e.getMessage(), e.isGuestException());
            return e.getMessage();
        }
    }

    /**
     * R7RS §5.3: "It is an error if a variable appears more than once in the set of <formals>" of
     * define-values. In a body two definitions of one variable were already an error (§5.3.2); at
     * the top level they were two definitions, the second silently winning. Both are now syntax
     * errors, at expansion, as for lambda.
     */
    @Test
    public void defineValuesWithADuplicateFormalIsAnError() {
        for (String formals : new String[]{"(x x)", "(x y x)", "(x . x)", "(x y . x)"}) {
            String e = error("(define-values " + formals + " (values 1 2 3))");
            assertTrue(formals + ": " + e, e.contains("duplicate parameter x"));
            e = error("(let () (define-values " + formals + " (values 1 2 3)) x)");
            assertTrue(formals + ": " + e, e.contains("duplicate"));
        }
        // Still fine: distinct formals, at the top level and in a body. The wrong number of values is an error.
        check("(1 2 (3))", "(let () (define-values (x y . z) (values 1 2 3)) (list x y z))");
        check("(define-values (a b c) (values 1 2 3))", "(1 2 3)", "(list a b c)");
        String e = error("(define-values (a b) (values 1 2 3))");
        assertTrue(e, e.contains("arguments"));
    }
}
