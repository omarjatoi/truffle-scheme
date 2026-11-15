package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.Test;

/**
 * M3-03: {@code syntax-rules} (R7RS §4.3.2). The derived forms arrive in M3-05, so programs here
 * get {@code let} and {@code cond} from {@link #HARNESS}, written with {@code syntax-rules} as in
 * R7RS §7.3.
 */
public class SyntaxRulesTest {

    private static final String SCHEME = "scheme";

    /**
     * R7RS §7.3's let (with named let) and cond, even?/odd?, and a stand-in for chibi's test shim:
     * {@code (test expect expr)} writes the expected and actual values on one line, separated by a
     * tab. (The shim itself needs guard, parameterize and string ports: M3-06 and M4.)
     */
    private static final String HARNESS = """
                    (define-syntax let
                      (syntax-rules ()
                        ((let ((name val) ...) body1 body2 ...)
                         ((lambda (name ...) body1 body2 ...) val ...))
                        ((let tag ((name val) ...) body1 body2 ...)
                         ((letrec* ((tag (lambda (name ...) body1 body2 ...))) tag) val ...))))
                    (define-syntax cond
                      (syntax-rules (else =>)
                        ((cond (else result1 result2 ...)) (begin result1 result2 ...))
                        ((cond (test => result)) (let ((temp test)) (if temp (result temp))))
                        ((cond (test => result) clause1 clause2 ...)
                         (let ((temp test)) (if temp (result temp) (cond clause1 clause2 ...))))
                        ((cond (test)) test)
                        ((cond (test) clause1 clause2 ...) (let ((temp test)) (if temp temp (cond clause1 clause2 ...))))
                        ((cond (test result1 result2 ...)) (if test (begin result1 result2 ...)))
                        ((cond (test result1 result2 ...) clause1 clause2 ...)
                         (if test (begin result1 result2 ...) (cond clause1 clause2 ...)))))
                    (define (even? n) (if (= n 0) #t (odd? (- n 1))))
                    (define (odd? n) (if (= n 0) #f (even? (- n 1))))
                    (define (test-begin . name) #f)
                    (define (test-end . name) #f)
                    (define-syntax test
                      (syntax-rules ()
                        ((_ expect expr) (begin (write expect) (display "\t") (write expr) (newline)))))
                    """;

    /** The program's output, after the harness. */
    private static String run(String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            context.eval(SCHEME, HARNESS);
            context.eval(SCHEME, program);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String value(String expr) {
        return run("(write " + expr + ")");
    }

    private static PolyglotException error(String program) {
        try (Context context = Context.create(SCHEME)) {
            context.eval(SCHEME, HARNESS);
            PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval(SCHEME, program));
            assertTrue(e.getMessage(), e.isGuestException());
            return e;
        }
    }

    /** Runs the program's (test expect expr) cases; returns how many, failing on any mismatch. */
    private static int cases(String program) {
        List<String> failures = new ArrayList<>();
        int count = 0;
        for (String line : run(program).split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            count++;
            String[] parts = line.split("\t", -1);
            if (parts.length != 2 || !parts[0].equals(parts[1])) {
                failures.add(line);
            }
        }
        assertTrue("failed: " + failures, failures.isEmpty());
        return count;
    }

    // ------------------------------------------------------------------------------------------
    // R7RS §4.3

    @Test
    public void r7rsExamples() {
        // §4.3.1: the use site's if is a variable; the macro's if is still the keyword.
        assertEquals("now", value("""
                        (let-syntax ((given-that (syntax-rules ()
                                                   ((_ test stmt1 stmt2 ...) (if test (begin stmt1 stmt2 ...))))))
                          (let ((if #t))
                            (given-that if (set! if 'now))
                            if))"""));
        // §4.3.1: the macro's x is the x where the macro was defined.
        assertEquals("outer", value("""
                        (let ((x 'outer))
                          (let-syntax ((m (syntax-rules () ((m) x))))
                            (let ((x 'inner))
                              (m))))"""));
        // §4.3.1: my-or's temp doesn't capture the use site's, and the use site's let and if are
        // variables while my-or's are keywords.
        assertEquals("7", value("""
                        (letrec-syntax
                            ((my-or (syntax-rules ()
                                      ((my-or) #f)
                                      ((my-or e) e)
                                      ((my-or e1 e2 ...) (let ((temp e1)) (if temp temp (my-or e2 ...)))))))
                          (let ((x #f) (y 7) (temp 8) (let odd?) (if even?))
                            (my-or x (let temp) (if y) y)))"""));
        // §4.3.2: be-like-begin, with an escaped ellipsis.
        assertEquals("4", run("""
                        (define-syntax be-like-begin
                          (syntax-rules ()
                            ((be-like-begin name)
                             (define-syntax name (syntax-rules () ((name expr (... ...)) (begin expr (... ...))))))))
                        (be-like-begin sequence)
                        (write (sequence 1 2 3 4))"""));
        // §4.3.2: a literal matches only an identifier with the same binding.
        assertEquals("ok", value("(let ((=> #f)) (cond (#t => 'ok)))"));
        assertEquals("2", value("(cond ((+ 1 1) => (lambda (x) x)))"));
    }

    /**
     * Chibi's section 4.3, run through {@link #HARNESS} instead of the (chibi test) shim. Its
     * leading {@code import} is ignored until libraries (M3-04).
     */
    @Test
    public void chibiSection() throws IOException {
        assertEquals(25, cases(SuiteReadSyntaxTest.resource("/r7rs/4.3.scm")));
    }

    // ------------------------------------------------------------------------------------------
    // Patterns and templates

    @Test
    public void ellipsisAtAnyDepth() {
        String defs = """
                        (define-syntax flat (syntax-rules () ((_ (a b ...) ...) '(a ... b ... ...))))
                        (define-syntax rev (syntax-rules () ((_ (a b ...) ...) '((b ... a) ...))))
                        (define-syntax deep (syntax-rules () ((_ ((x ...) ...) ...) '(x ... ... ...))))
                        (define-syntax keep (syntax-rules () ((_ k (x ...)) '((k x) ...))))
                        """;
        assertEquals("(1 4 2 3 5)", run(defs + "(write (flat (1 2 3) (4 5)))"));
        assertEquals("((2 3 1) (5 4) (6))", run(defs + "(write (rev (1 2 3) (4 5) (6)))"));
        assertEquals("(1 2 3 4 5)", run(defs + "(write (deep ((1 2) (3)) () ((4) () (5))))"));
        // A variable with no ellipsis is the same in every repetition.
        assertEquals("((k 1) (k 2))", run(defs + "(write (keep k (1 2)))"));
        assertEquals("()", run(defs + "(write (flat))"));
    }

    @Test
    public void tailVectorAndDottedPatterns() {
        String defs = """
                        (define-syntax last-first (syntax-rules () ((_ a ... y z) '(z y a ...))))
                        (define-syntax dotted (syntax-rules () ((_ a . b) '(b a))))
                        (define-syntax improper (syntax-rules () ((_ (a ... . r)) '((a ...) r))))
                        (define-syntax vec (syntax-rules () ((_ #(a ... z)) '#(z a ...)) ((_ x) 'not-a-vector)))
                        (define-syntax exact-vec (syntax-rules () ((_ #(a b)) '(a b)) ((_ x) 'no)))
                        """;
        assertEquals("(4 3 1 2)", run(defs + "(write (last-first 1 2 3 4))"));
        assertEquals("(2 1)", run(defs + "(write (last-first 1 2))"));
        assertEquals("((2 3) 1)", run(defs + "(write (dotted 1 2 3))"));
        assertEquals("(() 1)", run(defs + "(write (dotted 1))"));
        assertEquals("((1 2) 3)", run(defs + "(write (improper (1 2 . 3)))"));
        assertEquals("((1 2) ())", run(defs + "(write (improper (1 2)))"));
        assertEquals("#(3 1 2)", run(defs + "(write (vec #(1 2 3)))"));
        assertEquals("not-a-vector", run(defs + "(write (vec (1 2 3)))"));
        assertEquals("(1 2)", run(defs + "(write (exact-vec #(1 2)))"));
        assertEquals("no", run(defs + "(write (exact-vec #(1 2 3)))"));
    }

    @Test
    public void literalsUnderscoreDataAndCustomEllipsis() {
        String defs = """
                        (define-syntax arrow (syntax-rules (=>) ((_ a => b) 'arrow) ((_ a b c) 'plain)))
                        (define-syntax kind (syntax-rules () ((_ 1) 'one) ((_ "s") 'string) ((_ #\\c) 'char) ((_ #t) 'true) ((_ ()) 'empty) ((_ x) 'other)))
                        (define-syntax second (syntax-rules () ((_ _ x . _) 'x)))
                        (define-syntax colons (syntax-rules ::: () ((_ a :::) '(a ::: ...))))
                        """;
        assertEquals("arrow", run(defs + "(write (arrow 1 => 2))"));
        assertEquals("plain", run(defs + "(write (arrow 1 2 3))"));
        assertEquals("plain", run(defs + "(write ((lambda (=>) (arrow 1 => 2)) 0))"));
        assertEquals("(one string char true empty other other)",
                        run(defs + "(write (list (kind 1) (kind \"s\") (kind #\\c) (kind #t) (kind ()) (kind 2) (kind \"t\")))"));
        assertEquals("b", run(defs + "(write (second a b c d))"));
        assertEquals("(1 2 ...)", run(defs + "(write (colons 1 2))"));
    }

    @Test
    public void macrosWritingMacros() {
        // The inner syntax-rules' pattern variable x is the outer template's own x, not the use
        // site's, so 'y stays the use site's x.
        assertEquals("x", run("""
                        (define-syntax foo
                          (syntax-rules ()
                            ((foo bar y) (define-syntax bar (syntax-rules () ((bar x) 'y))))))
                        (foo bar x)
                        (write (bar 1))"""));
        // Recursive expansion through a macro that defines definitions in a body.
        assertEquals("(1 2 3)", run("""
                        (define-syntax define-all
                          (syntax-rules () ((_ (name val) ...) (begin (define name val) ...))))
                        (define (f) (define-all (a 1) (b 2) (c 3)) (list a b c))
                        (write (f))"""));
    }

    @Test
    public void errors() {
        assertTrue(error("(define-syntax m (syntax-rules () ((_ a) a))) (m)").getMessage().contains("no syntax-rules pattern matches"));
        assertTrue(error("(define-syntax m (syntax-rules () ((_ a ...) a))) (m 1)").getMessage().contains("needs 1 ellipses"));
        assertTrue(error("(define-syntax m (syntax-rules () ((_ a) (a ...)))) (m 1)").getMessage().contains("no pattern variable to repeat"));
        assertTrue(error("(define-syntax m (syntax-rules () ((_ ... a) a)))").getMessage().contains("misplaced ellipsis"));
        assertTrue(error("(define-syntax m (syntax-rules () ((_ a ... b ...) a)))").getMessage().contains("misplaced ellipsis"));
        assertTrue(error("(define-syntax m (syntax-rules () ((_ a a) a)))").getMessage().contains("duplicate pattern variable a"));
        assertTrue(error("(define-syntax m (syntax-rules () ((_ (a ...) (b ...)) '((a b) ...)))) (m (1 2) (3))").getMessage().contains("different numbers"));
        assertTrue(error("(define-syntax m (syntax-rules (1) ((_) 1)))").getMessage().contains("literals must be identifiers"));
    }

    /** Rule 4: no Java recursion proportional to the depth of patterns, templates or input. */
    @Test
    public void deepPatternsAndTemplatesDoNotRecurse() {
        int depth = 50_000;
        String open = "(".repeat(depth);
        String close = ")".repeat(depth);
        // A deep template, a deep pattern, and deep input matched by one variable.
        assertEquals("1", run("(define-syntax q (syntax-rules () ((_ x) '(x " + open + close + "))))" +
                        "(write (car (q 1)))"));
        assertEquals("2", run("(define-syntax p (syntax-rules () ((_ " + open + "x" + close + ") 'x)))" +
                        "(write (p " + open + "2" + close + "))"));
        assertEquals("ok", run("(define-syntax r (syntax-rules () ((_ x) 'ok)))" +
                        "(write (r " + open + close + "))"));
    }
}
