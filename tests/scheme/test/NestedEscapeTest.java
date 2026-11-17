package scheme.test;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.junit.Test;

import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.Instruction;

import scheme.compiler.Expander;
import scheme.compiler.ProgramCompiler;
import scheme.compiler.SyntacticEnvironment;
import scheme.runtime.GlobalCell;
import scheme.runtime.GlobalEnvironment;
import scheme.runtime.SchemeContext;
import scheme.runtime.value.SClosure;
import scheme.runtime.value.SymbolTable;

/**
 * M9-05: a {@code call/cc}'s receiver counts as only called (#173's containment rule, design.md
 * "Continuations") when the call/cc's own k is escape-only: call/cc calls its receiver once, in its
 * extent. So an outer k used inside a nested call/cc's receiver is escape-only too, and both calls
 * compile to {@code %call/ec}. The two are decided together, as one greatest fixed point: the
 * receiver is contained if its k is, and k may depend on values used in the receiver.
 *
 * <p>The analysis tests read the core AST ({@link Expander#show}), whose environment has no
 * {@code let} or {@code do}: a loop is written as {@code letrec*}. The semantics tests run under
 * {@code //tests:unit_torture} too, with a capture and resume at every non-tail call.
 */
public class NestedEscapeTest {

    private static final String SCHEME = "scheme";

    private final SymbolTable symbols = new SymbolTable();
    private final SyntacticEnvironment top = new SyntacticEnvironment();

    /** How many call/cc calls of a closed unit compile to %call/ec. */
    private int escapes(String program) {
        String ast = Expander.show(symbols, new GlobalEnvironment(), top, program, true);
        return ast.split("%call/ec", -1).length - 1;
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

    /** How many roots the procedure the interaction environment binds {@code name} to has, with the lambdas in it. */
    private static int roots(String program, String name) {
        try (Context context = Context.create(SCHEME)) {
            context.eval(SCHEME, program);
            context.enter();
            try {
                SchemeContext scheme = SchemeContext.get(null);
                GlobalCell cell = new ProgramCompiler().interactionCell(scheme, scheme.symbols.intern(name));
                List<BytecodeRootNode> roots = new ArrayList<>();
                roots.add((BytecodeRootNode) ((SClosure) cell.valueOrNull()).callTarget.getRootNode());
                for (int i = 0; i < roots.size(); i++) {
                    for (Instruction instruction : roots.get(i).getBytecodeNode().getInstructions()) {
                        for (Instruction.Argument argument : instruction.getArguments()) {
                            if (argument.getKind() == Instruction.Argument.Kind.CONSTANT && argument.asConstant() instanceof BytecodeRootNode nested) {
                                roots.add(nested);
                            }
                        }
                    }
                }
                return roots.size();
            } finally {
                context.leave();
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // The analysis

    @Test
    public void anOuterKInANestedReceiverEscapes() {
        // Directly, and through a helper of the outer receiver.
        assertEquals(2, escapes("(call/cc (lambda (k) (call/cc (lambda (j) (k 7)))))"));
        assertEquals(2, escapes("(call/cc (lambda (k) (define (h) (k 1)) (+ 1 (call/cc (lambda (j) (h) (j 2))))))"));
        // Three deep, each k used in the innermost receiver.
        assertEquals(3, escapes("(call/cc (lambda (a) (call/cc (lambda (b) (+ 1 (call/cc (lambda (c) (if x (a 1) (if y (b 2) (c 3))))))))))"));
        // Both ks passed to a known procedure that calls them: one fixed point decides both.
        assertEquals(2, escapes("(define (pick x k j) (if x (k x) (j 0))) (define (f x) (call/cc (lambda (k) (call/cc (lambda (j) (pick x k j))))))"));
        // In a loop of the inner receiver, and in a loop around the inner call/cc.
        assertEquals(2, escapes("""
                        (call/cc (lambda (k)
                                   (+ 1 (call/cc (lambda (j)
                                                   ((letrec* ((loop (lambda (i) (if (= i 10) 0 (begin (if (= i 5) (k i)) (loop (+ i 1)))))))
                                                      loop)
                                                    0))))))"""));
        assertEquals(2, escapes("""
                        (call/cc (lambda (k)
                                   ((letrec* ((loop (lambda (i) (if (= i 10) 0 (begin (call/cc (lambda (j) (if (= i 5) (k i) (j i)))) (loop (+ i 1)))))))
                                      loop)
                                    0)))"""));
    }

    @Test
    public void anOuterKInANestedReceiverWhoseKEscapesCaptures() {
        // The inner k escapes: stored, returned, put in a vector, passed to an unknown procedure, or
        // assigned. Neither call/cc escapes then: the inner one captures, and so the outer one does.
        assertEquals(0, escapes("(define saved #f) (call/cc (lambda (k) (call/cc (lambda (j) (set! saved j) (k 1)))))"));
        assertEquals(0, escapes("(call/cc (lambda (k) (call/cc (lambda (j) (k 1) j))))"));
        assertEquals(0, escapes("(call/cc (lambda (k) (call/cc (lambda (j) (vector j (k 1))))))"));
        assertEquals(0, escapes("(call/cc (lambda (k) (call/cc (lambda (j) (g j) (k 1)))))"));
        assertEquals(0, escapes("(call/cc (lambda (k) (call/cc (lambda (j) (set! j 1) (k 1)))))"));
        // Through a helper that lets the inner k escape.
        assertEquals(0, escapes("(define saved #f) (define (keep x) (set! saved x)) (call/cc (lambda (k) (call/cc (lambda (j) (keep j) (k 1)))))"));
        // The nested call/cc is in a lambda that escapes, or is passed to map: only it escapes.
        assertEquals(1, escapes("(define saved #f) (call/cc (lambda (k) (set! saved (lambda () (call/cc (lambda (j) (k 1)))))))"));
        assertEquals(1, escapes("(define (f l) (call/cc (lambda (k) (map (lambda (x) (call/cc (lambda (j) (if x (k x) (j 0))))) l))))"));
        // The outer k escapes some other way: the inner call/cc still escapes on its own.
        assertEquals(1, escapes("(define saved #f) (call/cc (lambda (k) (set! saved k) (call/cc (lambda (j) (k 1)))))"));
        // A receiver that is not a literal lambda.
        assertEquals(0, escapes("(call/cc (lambda (k) (call/cc (car (list (lambda (j) (k 1)))))))"));
    }

    @Test
    public void aLoopInANestedReceiverThatCallsTheOuterKIsContified() {
        // M9-05's contification of a loop whose only unknown non-tail call is of an escape-only k
        // covers the outer k too: the procedure, the outer receiver and the inner one; no loop root.
        assertEquals(3, roots("""
                        (define (f v)
                          (call/cc
                            (lambda (outer)
                              (+ 1 (call/cc
                                     (lambda (inner)
                                       (do ((i 0 (+ i 1))) ((= i (vector-length v)) 0)
                                         (if (negative? (vector-ref v i)) (outer 'negative))
                                         (if (zero? (vector-ref v i)) (inner i)))))))))
                        (f (vector 1 0))
                        """, "f"));
    }

    // ------------------------------------------------------------------------------------------
    // Semantics: the same with an escape as with a capture

    @Test
    public void nestedEscapes() {
        check("(7 3 negative 2 1 (10 0) (a b 1))", """
                        (define (f v)
                          (call/cc
                            (lambda (outer)
                              (+ 1 (call/cc
                                     (lambda (inner)
                                       (do ((i 0 (+ i 1))) ((= i (vector-length v)) 0)
                                         (if (negative? (vector-ref v i)) (outer 'negative))
                                         (if (zero? (vector-ref v i)) (inner i)))))))))
                        (define (pick x k j) (if x (k x) (j 0)))
                        (define (g x) (list (call/cc (lambda (k) (+ 10 (call/cc (lambda (j) (pick x k j))))))))
                        (define (three x)
                          (call/cc (lambda (a) (call/cc (lambda (b) (+ 1 (call/cc (lambda (c) (case x ((1) (a 'a)) ((2) (b 'b)) (else (c 0)))))))))))
                        (write (list (call/cc (lambda (k) (call/cc (lambda (j) (k 7)))))
                                     (call/cc (lambda (k) (define (h) (k 3)) (+ 1 (call/cc (lambda (j) (h) (j 2))))))
                                     (f (vector 1 -1 0))
                                     (f (vector 1 0 -1))
                                     (f (vector))
                                     (append (g #f) (g 0))
                                     (list (three 1) (three 2) (three 3))))
                        """);
    }

    @Test
    public void nestedEscapesLeaveDynamicWindExtents() {
        // The outer k, invoked in the inner receiver inside a dynamic-wind, runs the after thunk;
        // the inner one leaves only its own.
        check("((outer-after) 5 (inner-after) 13)", """
                        (define log '())
                        (define (note x) (set! log (cons x log)))
                        (define a (call/cc (lambda (k)
                                    (dynamic-wind
                                      (lambda () #f)
                                      (lambda () (call/cc (lambda (j) (dynamic-wind (lambda () #f) (lambda () (k 5)) (lambda () (note 'outer-after))))))
                                      (lambda () #f)))))
                        (define log1 log)
                        (set! log '())
                        (define b (call/cc (lambda (k)
                                    (+ 10 (call/cc (lambda (j) (dynamic-wind (lambda () #f) (lambda () (j 3)) (lambda () (note 'inner-after)))))))))
                        (write (list log1 a log b))
                        """);
    }

    @Test
    public void reenteringAContinuationCapturedInANestedReceiver() {
        // A full continuation captured inside the inner receiver, re-entered after both call/ccs
        // returned: the outer k still escapes, from every copy.
        check("(first again again)", """
                        (define (test)
                          (define resume #f)
                          (define results '())
                          (define n 0)
                          (define (f)
                            (call/cc
                              (lambda (k)
                                (call/cc
                                  (lambda (j)
                                    (call/cc (lambda (c) (set! resume c)))
                                    (set! n (+ n 1))
                                    (k (if (= n 1) 'first 'again)))))))
                          (set! results (cons (f) results))
                          (if (< (length results) 3) (resume #f))
                          (reverse results))
                        (write (test))
                        """);
    }
}
