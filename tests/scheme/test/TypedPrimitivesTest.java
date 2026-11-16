package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.Test;

import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.Instruction;

import scheme.compiler.ProgramCompiler;
import scheme.runtime.GlobalCell;
import scheme.runtime.SchemeContext;
import scheme.runtime.value.SClosure;

/**
 * M7-03: a call of one of the hottest primitives ({@code car}, {@code +}, {@code <},
 * {@code vector-ref}, ...) with a fixed number of arguments is a Bytecode DSL operation with typed
 * fast paths, and boxing elimination keeps fixnums, flonums and booleans unboxed between those
 * operations, in locals, and out of {@code long[]} and {@code double[]} vectors. Only a call of the
 * primitive's own variable is one: a program or the REPL can still define its own {@code car}.
 * Everything the fast paths don't take (bignums, mixed exactness, errors) goes to the primitive,
 * and errors are raised in the calling frame (design.md D8). Under {@code //tests:unit_torture}
 * these run with a capture and resume at every non-tail call.
 */
public class TypedPrimitivesTest {

    private static final String SCHEME = "scheme";

    private static String run(String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            context.eval(SCHEME, program);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** {@code expr}'s value, written, after {@code defs}: as an isolated program, then in the interaction environment. */
    private static void check(String defs, String expected, String expr) {
        String body = defs + "\n(write " + expr + ")";
        assertEquals(expr, expected, run("(import (scheme base) (scheme write) (scheme inexact) (scheme char))\n" + body));
        assertEquals(expr, expected, run(body));
    }

    /**
     * The root of the procedure the interaction environment binds {@code name} to, after
     * {@code program} ran: its instructions are quickened for the values they saw.
     */
    private static BytecodeRootNode root(Context context, String program, String name) {
        context.eval(SCHEME, program);
        context.enter();
        try {
            SchemeContext scheme = SchemeContext.get(null);
            GlobalCell cell = new ProgramCompiler().interactionCell(scheme, scheme.symbols.intern(name));
            return (BytecodeRootNode) ((SClosure) cell.valueOrNull()).callTarget.getRootNode();
        } finally {
            context.leave();
        }
    }

    private static List<String> names(BytecodeRootNode root) {
        List<String> names = new ArrayList<>();
        for (Instruction instruction : root.getBytecodeNode().getInstructions()) {
            names.add(instruction.getName());
        }
        return names;
    }

    /** The instructions whose name has every one of {@code parts}, ignoring case. */
    private static List<String> matching(List<String> names, String... parts) {
        List<String> result = new ArrayList<>();
        for (String name : names) {
            String lower = name.toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String part : parts) {
                all &= lower.contains(part.toLowerCase(Locale.ROOT));
            }
            if (all) {
                result.add(name);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------------------------------
    // Operations, unboxed

    @Test
    public void fixnumArithmeticInALoopIsUnboxed() {
        try (Context context = Context.create(SCHEME)) {
            List<String> code = names(root(context, """
                            (define (sum n)
                              (let loop ((i 0) (acc 0))
                                (if (< i n)
                                    (loop (+ i 1) (+ acc (* i 2)))
                                    acc)))
                            (sum 1000) (sum 1000)
                            """, "sum"));
            // No call at all: the arithmetic and the comparison are operations, the loop a jump.
            assertFalse(code.toString(), code.contains("c.Call"));
            // Each operation took fixnums and produced one its consumer takes unboxed: the loop's
            // variables are long locals, and < gives the branch an unboxed boolean.
            assertFalse(code.toString(), matching(code, "c.Add", "long", "unboxed").isEmpty());
            assertFalse(code.toString(), matching(code, "c.Mul", "long", "unboxed").isEmpty());
            assertFalse(code.toString(), matching(code, "c.Compare", "long", "unboxed").isEmpty());
            assertTrue(code.toString(), matching(code, "c.Truthy").isEmpty());
            assertFalse(code.toString(), matching(code, "store.local", "long").isEmpty());
            assertFalse(code.toString(), matching(code, "load.local", "long", "unboxed").isEmpty());
        }
    }

    @Test
    public void flonumArithmeticAndVectorsAreUnboxed() {
        try (Context context = Context.create(SCHEME)) {
            List<String> code = names(root(context, """
                            (define (scale! v k)
                              (let loop ((i 0))
                                (when (< i (vector-length v))
                                  (vector-set! v i (* k (vector-ref v i)))
                                  (loop (+ i 1)))))
                            (define v (make-vector 100 1.5))
                            (scale! v 2.0) (scale! v 2.0)
                            """, "scale!"));
            assertFalse(code.toString(), code.contains("c.Call"));
            // The double[] vector's element is read unboxed, multiplied unboxed, stored unboxed.
            assertFalse(code.toString(), matching(code, "c.VectorRef", "double", "unboxed").isEmpty());
            assertFalse(code.toString(), matching(code, "c.Mul", "double", "unboxed").isEmpty());
            assertFalse(code.toString(), matching(code, "c.VectorSet", "double").isEmpty());
        }
        check("(define v (vector 1 2 3)) (define (sum v) (let loop ((i 0) (s 0)) (if (= i (vector-length v)) s (loop (+ i 1) (+ s (vector-ref v i))))))",
                        "(6 7.5)", "(list (sum v) (sum (vector 1.5 2.5 3.5)))");
    }

    @Test
    public void listPrimitivesAreOperations() {
        try (Context context = Context.create(SCHEME)) {
            List<String> code = names(root(context, """
                            (define (rev l)
                              (let loop ((l l) (acc '()))
                                (if (null? l) acc (loop (cdr l) (cons (car l) acc)))))
                            (rev '(1 2 3)) (rev '(1 2 3))
                            """, "rev"));
            assertFalse(code.toString(), code.contains("c.Call"));
            for (String operation : new String[]{"c.IsNull", "c.Car", "c.Cdr", "c.Cons"}) {
                assertFalse(operation + " in " + code, matching(code, operation).isEmpty());
            }
        }
        check("", "((3 2 1) #t #f #t #f #t #f #t)",
                        "(list (let loop ((l '(1 2 3)) (acc '())) (if (null? l) acc (loop (cdr l) (cons (car l) acc)))) (pair? '(1)) (pair? '()) (not #f) (not 0) (eq? 'a 'a) (eq? '(a) '(a)) (zero? 0))");
    }

    // ------------------------------------------------------------------------------------------
    // Semantics: what the primitive does, the fast paths included

    @Test
    public void fixnumsOverflowIntoBignumsAndBack() {
        // The site sees fixnums first, so its fast path is the fixnum one, then an overflow.
        String defs = "(define (add a b) (+ a b)) (define (sub a b) (- a b)) (define (mul a b) (* a b))" +
                        " (define (warm) (let loop ((i 0)) (when (< i 1000) (add i 1) (sub i 1) (mul i 2) (loop (+ i 1)))))" +
                        " (warm)";
        check(defs, "(9223372036854775808 -9223372036854775809 18446744073709551614 9223372036854775807 #t 5)",
                        "(list (add 9223372036854775807 1) (sub -9223372036854775808 1) (mul 9223372036854775807 2)" +
                                        " (sub (add 9223372036854775807 1) 1) (exact-integer? (sub (add 9223372036854775807 1) 1)) (add 2 3))");
        // And in a loop whose sum leaves the fixnums.
        check("", "(23058430092136939520 #t)",
                        "(let loop ((i 0) (s 4611686018427387904)) (if (= i 4) (list s (exact? s)) (loop (+ i 1) (+ s 4611686018427387904))))");
    }

    @Test
    public void exactnessAndTheOtherNumbers() {
        check("", "(3.5 3.5 #t #f #t 5/6 1.5 #f #f #t #t 2.0 1/2 #t)",
                        "(list (+ 1 2.5) (+ 2.5 1) (< 1 2.5) (< 2.5 1) (= 1 1.0) (+ 1/2 1/3) (* 3 1/2 1.0) (< +nan.0 1.0) (= +nan.0 +nan.0)" +
                                        " (= 0.0 -0.0) (zero? -0.0) (- 3.0 1) (- 1 1/2) (> 3+0i 2))");
        check("", "(#t #f #t #t #f)", "(list (<= 1 1) (<= 2 1) (>= 2 2.0) (> 1.5 1) (>= 1/3 1/2))");
    }

    @Test
    public void divisions() {
        // Each site sees fixnums first, then the cases its fast path leaves to the primitive.
        String defs = "(define (q a b) (quotient a b)) (define (r a b) (remainder a b)) (define (m a b) (modulo a b)) (define (d a b) (/ a b))" +
                        " (let loop ((i 1)) (when (< i 1000) (q i 7) (r i 7) (m i 7) (d (* i 6) 3) (d (* i 1.5) 0.5) (loop (+ i 1))))" +
                        " (define-syntax message (syntax-rules () ((_ e) (guard (c (#t (error-object-message c))) e))))";
        check(defs, "(-3 1 -1 1 3 -1 9223372036854775808 0 0 3.0 1.0 2 1/3 0.25 +inf.0 -2)",
                        "(list (q 7 -2) (r 7 -2) (m 7 -2) (m -7 2) (q -7 -2) (r -7 -2) (q -9223372036854775808 -1) (r -9223372036854775808 -1)" +
                                        " (m -9223372036854775808 -1) (q 7.0 2) (r 7.0 2) (d 6 3) (d 1 3) (d 1.0 4.0) (d 1.0 0.0) (d -9223372036854775808 4611686018427387904))");
        check(defs, "(#t #t #t #t)", "(map string? (list (message (q 7 0)) (message (r 7 0)) (message (m 7 0)) (message (d 1 0))))");
    }

    @Test
    public void flonumsAreEqToThemselves() {
        // A flonum in a local, a double[] vector or an argument is boxed anew each time it is read as
        // an object: eq? compares flonums by value, so boxing never shows (design.md D1).
        check("", "(#t #t #t #t)", "(let* ((x (* 1.5 2.0)) (v (make-vector 1 x))) (list (eq? x x) (eq? (vector-ref v 0) x) (and (memq x (list 1 x)) #t) ((lambda (y) (eq? y x)) x)))");
    }

    @Test
    public void vectorStoragesGeneralize() {
        check("(define v (make-vector 3 0)) (define (set v i x) (vector-set! v i x)) (define (ref v i) (vector-ref v i))",
                        "(1 a 2.5 (1 a 2.5))", "(begin (set v 0 1) (set v 1 'a) (set v 2 2.5) (list (ref v 0) (ref v 1) (ref v 2) (vector->list v)))");
        check("(define d (make-vector 2 0.5)) (define (set v i x) (vector-set! v i x))", "#(1 0.5)", "(begin (set d 0 1) d)");
    }

    // ------------------------------------------------------------------------------------------
    // Errors: the primitive's, raised in the calling frame

    @Test
    public void errorsAreThePrimitives() {
        String guard = "(define-syntax message (syntax-rules () ((_ e) (guard (c (#t (error-object-message c))) e))))";
        check(guard, "(\"car: not a pair: 1\" \"cdr: not a pair: ()\" \"vector-ref: not a valid index for a vector of length 2: 2\"" +
                        " \"vector-set!: vector is immutable (a literal)\")",
                        "(list (message (car 1)) (message (cdr '())) (message (vector-ref (vector 1 2) 2)) (message (vector-set! #(1 2) 0 3)))");
        check(guard + " (define (f x) (+ 1 x))", "(#t #t)",
                        "(list (string? (message (f 'a))) (string? (message (< 1 'b))))");
    }

    @Test
    public void errorsAreRaisedInTheCallingFrame() {
        // The handler runs in the frame that called car: a continuation captured there resumes it.
        check("(define (g x) (+ 100 (car x)))", "(101 (1 . 101))",
                        "(list (call/cc (lambda (k) (with-exception-handler (lambda (e) (k 101)) (lambda () (g 1)))))" +
                                        " (cons 1 (+ 100 (call/cc (lambda (k) (with-exception-handler (lambda (e) (k 1)) (lambda () (+ 0 (car 1)))))))))");
        // In tail position too.
        check("(define (f x) (car x))", "(caught 7)", "(list (guard (e (#t 'caught)) (f 1)) (+ 1 (guard (e (#t 6)) (f 1))))");
    }

    @Test
    public void anUncaughtErrorNamesTheExpression() {
        Source source = Source.newBuilder(SCHEME, """
                        (define (g x)
                          (+ 1
                             (car x)))
                        (g 5)
                        """, "located.scm").buildLiteral();
        try (Context context = Context.create(SCHEME)) {
            PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval(source));
            assertTrue(e.getMessage(), e.getMessage().contains("car: not a pair: 5"));
            List<String> frames = new ArrayList<>();
            for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
                if (frame.isGuestFrame() && frame.getSourceLocation() != null) {
                    frames.add(frame.getRootName() + "@" + frame.getSourceLocation().getStartLine() + ":" + frame.getSourceLocation().getStartColumn());
                }
            }
            // g's frame is at (car x), where the error happened; the program's at (g 5).
            assertTrue(frames.toString(), frames.contains("g@3:6"));
            assertTrue(frames.toString(), frames.contains("<program>@4:1"));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Redefinition: only the primitive's own variable is an operation

    @Test
    public void aProgramMayDefineItsOwnCar() {
        assertEquals("(mine mine 3)", run("""
                        (import (scheme base) (scheme write))
                        (define (f x) (car x))
                        (define (car x) 'mine)
                        (define (+ a b) (* a b))
                        (write (list (f '(1 2)) (car '(1 2)) (+ 1 3)))
                        """));
        // A renamed import is still the primitive.
        assertEquals("1", run("(import (rename (scheme base) (car kar)) (scheme write)) (define (f x) (kar x)) (write (f '(1 2)))"));
    }

    @Test
    public void theReplMayRedefineCar() {
        try (Context context = Context.create(SCHEME)) {
            context.eval(SCHEME, "(define (f x) (car x)) (define (h x) (+ 1 (car x)))");
            for (int i = 0; i < 1000; i++) {
                assertEquals(1L, context.eval(SCHEME, "(f '(1 2))").asLong());
            }
            // The definition makes the interaction environment's own car (design.md D15): code
            // compiled from now on calls it; f and h still mean the import they were compiled with.
            context.eval(SCHEME, "(define (car x) 'mine)");
            assertEquals("mine", context.eval(SCHEME, "(symbol->string (car '(1 2)))").asString());
            assertEquals("mine", context.eval(SCHEME, "(symbol->string ((lambda (x) (car x)) '(1 2)))").asString());
            assertEquals(1L, context.eval(SCHEME, "(f '(1 2))").asLong());
            assertEquals(2L, context.eval(SCHEME, "(h '(1 2))").asLong());
            // ... which can be assigned, and the code that calls it sees each new value.
            context.eval(SCHEME, "(define (g x) (car x)) (set! car cadr)");
            assertEquals(2L, context.eval(SCHEME, "(g '(1 2))").asLong());
            context.eval(SCHEME, "(set! car (lambda (x) (call/cc (lambda (k) (k 'escaped)))))");
            assertEquals("escaped", context.eval(SCHEME, "(symbol->string (g '(1 2)))").asString());
        }
    }
}
