package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.Test;

import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.Instruction;

import scheme.compiler.Expander;
import scheme.compiler.SyntacticEnvironment;
import scheme.runtime.GlobalEnvironment;
import scheme.runtime.value.SClosure;
import scheme.runtime.value.SymbolTable;

/**
 * M5-05: Fixing Letrec and closure-free {@code let} (design.md D2).
 *
 * <p>The analysis tests read the core AST ({@link Expander#show}), with each boxed variable's name
 * ending in {@code !}; the code tests read a procedure's bytecode. The semantics tests run under
 * {@code //tests:unit_torture} too, with a capture and resume at every non-tail call.
 */
public class FixingLetrecTest {

    private static final String SCHEME = "scheme";

    private final SymbolTable symbols = new SymbolTable();
    private final SyntacticEnvironment top = new SyntacticEnvironment();

    /**
     * The core AST, each boxed variable's name ending with {@code !}. Only the core forms are
     * bound: a let is written as what it expands to, {@code ((lambda (x ...) body) init ...)}, and
     * a named let as {@code ((letrec* ((tag (lambda (x ...) body))) tag) init ...)}.
     */
    private String show(String program) {
        return Expander.show(symbols, new GlobalEnvironment(), top, program, false, true);
    }

    private static String run(String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            context.eval(SCHEME, program);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** As an isolated program, then as text in the interaction environment. */
    private static void check(String expected, String body) {
        assertEquals(body, expected, run("(import (scheme base) (scheme write))\n" + body));
        assertEquals(body, expected, run(body));
    }

    /** The names of the instructions of the procedure that {@code name} is bound to after {@code program}. */
    private static List<String> instructions(String program, String name) {
        try (Context context = Context.create(SCHEME)) {
            ResultProbe probe = context.getEngine().getInstruments().get(ResultProbe.ID).lookup(ResultProbe.class);
            context.eval(SCHEME, program);
            context.eval(Source.newBuilder(SCHEME, name, ResultProbe.SOURCE).buildLiteral());
            BytecodeRootNode root = (BytecodeRootNode) ((SClosure) probe.last).callTarget.getRootNode();
            List<String> names = new ArrayList<>();
            for (Instruction instruction : root.getBytecodeNode().getInstructions()) {
                names.add(instruction.getName());
            }
            return names;
        }
    }

    private static boolean has(List<String> instructions, String operation) {
        for (String name : instructions) {
            if (name.toLowerCase(java.util.Locale.ROOT).contains(operation.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------
    // Closure-free let

    @Test
    public void aLiteralLambdaCalledWithItsArityIsALet() {
        assertEquals("(lambda (a.1) (let ((x.2 a.1) (y.3 2)) (+ x.2 y.3)))", show("(lambda (a) ((lambda (x y) (+ x y)) a 2))"));
        assertEquals("(lambda (a.1) (let ((x.2 a.1)) (if x.2 x.2 (f))))", show("(lambda (a) ((lambda (x) (if x x (f))) a))"));
        assertEquals("(let () 1)", show("((lambda () 1))"));
        // A rest parameter, or another number of arguments, stays a call.
        assertEquals("((lambda (x.1 . r.2) r.2) 1 2)", show("((lambda (x . r) r) 1 2)"));
        assertEquals("((lambda (x.1) x.1))", show("((lambda (x) x))"));
    }

    @Test
    public void aNamedLetCallsItsProcedureInsideTheLetrec() {
        assertEquals("(lambda (n.1) (letrec* ((loop.2 (lambda (i.3) (if (= i.3 0) i.3 (loop.2 (- i.3 1)))))) (loop.2 n.1)))",
                        show("(lambda (n) ((letrec* ((loop (lambda (i) (if (= i 0) i (loop (- i 1)))))) loop) n))"));
    }

    @Test
    public void aLetVariableIsBoxedOnlyIfAssigned() {
        assertEquals("(lambda (a.1) (let ((x.2! a.1)) (begin (set! x.2! 1) x.2!)))", show("(lambda (a) ((lambda (x) (set! x 1) x) a))"));
        // A let's variables are the enclosing root's: a letrec* variable referred to in a let body isn't captured.
        assertEquals("(lambda () (letrec* ((v.1 (f))) (let ((x.2 1)) (cons x.2 v.1))))", show("(lambda () (define v (f)) ((lambda (x) (cons x v)) 1))"));
    }

    @Test
    public void aProcedureWithLetsMakesNoClosure() {
        List<String> code = instructions("(define (f a) (let* ((x (+ a 1)) (y (* x 2))) (or (> y 100) (- y x))))", "f");
        assertTrue(code.toString(), !has(code, "MakeClosure"));
        assertEquals(3L, evalLong("(define (f a) (let* ((x (+ a 1)) (y (* x 2))) (or (> y 100) (- y x)))) (f 2)"));
    }

    private static long evalLong(String program) {
        try (Context context = Context.create(SCHEME)) {
            return context.eval(SCHEME, program).asLong();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Fixing Letrec

    @Test
    public void lambdasOfALetrecAreNotBoxed() {
        assertEquals("(lambda (n.1) (letrec* ((ev?.2 (lambda (k.3) (od?.4 k.3))) (od?.4 (lambda (k.5) (ev?.2 k.5)))) (ev?.2 n.1)))",
                        show("(lambda (n) (define (ev? k) (od? k)) (define (od? k) (ev? k)) (ev? n))"));
        assertEquals("(lambda (n.1) (letrec* ((loop.2 (lambda (i.3) (lambda () (loop.2 i.3))))) (loop.2 n.1)))",
                        show("(lambda (n) ((letrec* ((loop (lambda (i) (lambda () (loop i))))) loop) n))"));
    }

    @Test
    public void aLetrecVariableIsBoxedIfAClosureCanSeeItUninitialized() {
        // h is captured by g, which (g) calls before h is initialized: g's closure can't be patched.
        assertEquals("(lambda () (letrec* ((g.1 (lambda () (lambda () h.2!))) (x.3 (g.1)) (h.2! (lambda () 1))) (x.3)))",
                        show("(lambda () (define (g) (lambda () h)) (define x (g)) (define (h) 1) (x))"));
        // Assigned: boxed.
        assertEquals("(lambda () (letrec* ((f.1! (lambda () f.1!))) (begin (set! f.1! 1) f.1!)))", show("(lambda () (define (f) f) (set! f 1) f)"));
        // Not a lambda, and captured: boxed.
        assertEquals("(lambda () (letrec* ((v.1! (f)) (g.2 (lambda () v.1!))) g.2))", show("(lambda () (define v (f)) (define (g) v) g)"));
    }

    @Test
    public void aNamedLetLoopsWithoutABox() {
        List<String> code = instructions("(define (f n) (let loop ((i 0) (acc '())) (if (= i n) acc (loop (+ i 1) (cons i acc)))))", "f");
        assertTrue(code.toString(), !has(code, "MakeBox") && !has(code, "BoxRef"));
        assertEquals("(2 1 0)", run("(define (f n) (let loop ((i 0) (acc '())) (if (= i n) acc (loop (+ i 1) (cons i acc))))) (display (f 3))"));
    }

    @Test
    public void fixedLetrecSemantics() {
        // Mutual recursion, in tail position, deep: the patched closures see each other.
        check("#t", "(define (f n) (define (ev? n) (if (= n 0) #t (od? (- n 1)))) (define (od? n) (if (= n 0) #f (ev? (- n 1)))) (ev? n)) (display (f 1000000))");
        // The group's closures escape, and are called from outside.
        check("b", "(define (mk) (define (a n) (if (= n 0) 'a (b (- n 1)))) (define (b n) (if (= n 0) 'b (a (- n 1)))) a) (display ((mk) 5))");
        // A procedure's own name is the running closure.
        check("#t", "(define (mk) (letrec ((loop (lambda (n) (if (= n 0) loop (loop (- n 1)))))) loop)) (define l (mk)) (display (eq? l (l 3)))");
        check("#f", "(define (mk) (letrec ((loop (lambda (n) (if (= n 0) loop (loop (- n 1)))))) loop)) (display (eq? (mk) ((mk) 3)))");
        // A closure made before the letrec* variable it refers to is initialized sees its value.
        check("1", "(define (f) (define (g) (lambda () (h))) (define x (g)) (define (h) 1) (x)) (display (f))");
        // Closures made in a loop capture their own iteration's variables.
        check("(2 1 0)", "(define (f) (let loop ((i 0) (fs '())) (if (= i 3) (map (lambda (g) (g)) fs) (loop (+ i 1) (cons (lambda () i) fs))))) (display (f))");
        // A nested lambda refers to its enclosing lambda's own name.
        check("3", "(define (f) (define (count n) (if (= n 0) 0 ((lambda () (+ 1 (count (- n 1))))))) (count 3)) (display (f))");
    }

    // ------------------------------------------------------------------------------------------
    // Semantics of an inlined let

    @Test
    public void letSemantics() {
        // set! of a let variable is seen by a closure over it, and the other way round.
        check("2", "(define (f) (let ((x 1)) (let ((g (lambda () x))) (set! x 2) (g)))) (display (f))");
        check("5", "(define (f) (let ((x 1)) (let ((s (lambda (v) (set! x v)))) (s 5) x))) (display (f))");
        // A let's body is in tail position: a million iterations through it.
        check("done", "(define (count n) (let ((m n)) (if (= m 0) 'done (count (- m 1))))) (display (count 1000000))");
        check("done", "(define (f n) (let ((m (- n 1))) (if (= m 0) 'done (g m)))) (define (g n) (or #f (f n))) (display (f 1000000))");
        // A let inside a self-loop gets fresh boxes each iteration.
        check("(2 1 0)", "(define (f i fs) (let ((x i)) (set! x x) (if (= i 3) (map (lambda (g) (g)) fs) (f (+ i 1) (cons (lambda () x) fs))))) (display (f 0 '()))");
        // let, let*, or and named let at the top level of a program.
        check("6", "(let* ((a 1) (b (+ a 1)) (c (or #f (+ a b)))) (display (let loop ((i c) (acc 0)) (if (= i 0) acc (loop (- i 1) (+ acc i))))))");
        // Shadowing.
        check("(1 2)", "(define (f x) (let ((x 2) (y x)) (list y x))) (display (f 1))");
    }

    @Test
    public void reenteringAnInlinedLetBody() {
        // Each return from an init's continuation binds the let's variables afresh, as a call would:
        // x's box is new each time.
        check("(21 11 11)", """
                        (define (test)
                          (define k #f)
                          (define results '())
                          (define (f)
                            (let ((x 1) (y (call/cc (lambda (c) (set! k c) 10))))
                              (set! x (+ x y))
                              x))
                          (set! results (cons (f) results))
                          (if (< (length results) 3) (k (* 10 (length results))))
                          results)
                        (display (test))""");
        // A continuation captured in a let body sees the let's variables when re-entered, and a
        // set! variable keeps its box.
        check("(3 2 1)", """
                        (define (test)
                          (define k #f)
                          (define out '())
                          (let ((a 1))
                            (let ((n (call/cc (lambda (c) (set! k c) 0))))
                              (set! a (+ a 1))
                              (set! out (cons (+ n a -1) out))))
                          (if (< (length out) 3) (k 0))
                          out)
                        (display (test))""");
    }

    @Test
    public void escapeAnalysisSeesThroughLets() {
        assertTrue(show("(call/cc (lambda (k) ((lambda (x) (k x)) 1)))").contains("%call/ec"));
        assertTrue(show("(call/cc (lambda (k) ((lambda (j) (j 1)) k)))").contains("%call/ec"));
        assertTrue(!show("(call/cc (lambda (k) ((lambda (j) (set! j 1) (j 1)) k)))").contains("%call/ec"));
        check("1", "(display (call/cc (lambda (k) (let ((j k)) (+ 10 (j 1))))))");
    }
}
