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

import scheme.compiler.ProgramCompiler;
import scheme.runtime.GlobalCell;
import scheme.runtime.SchemeContext;
import scheme.runtime.value.SClosure;

/**
 * M9-05: a loop whose only non-tail call of a procedure it can't know is a call of an escape-only
 * k (M5-04, #173: {@code %call/ec}'s continuation) is contified (M7-02), like a loop that calls only
 * known procedures. {@code puzzle}'s and {@code quicksort}'s {@code do} loops call {@code return}
 * that way. M7-02 keeps a loop with an unknown non-tail call a procedure of its own, so Truffle can
 * split it for each caller when that call site goes megamorphic; k's call always goes to the same
 * escape procedure's code, and never returns to the loop.
 *
 * <p>The semantics tests run under {@code //tests:unit_torture} too, with a capture and resume at
 * every non-tail call, across live escape frames.
 */
public class EscapeLoopTest {

    private static final String SCHEME = "scheme";

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

    /**
     * The roots of the procedure the interaction environment binds {@code name} to, after
     * {@code program}, and of the lambdas in it, recursively; and the names of their instructions.
     */
    private static List<List<String>> roots(String program, String name) {
        try (Context context = Context.create(SCHEME)) {
            context.eval(SCHEME, program);
            context.enter();
            try {
                SchemeContext scheme = SchemeContext.get(null);
                GlobalCell cell = new ProgramCompiler().interactionCell(scheme, scheme.symbols.intern(name));
                List<BytecodeRootNode> roots = new ArrayList<>();
                roots.add((BytecodeRootNode) ((SClosure) cell.valueOrNull()).callTarget.getRootNode());
                List<List<String>> names = new ArrayList<>();
                for (int i = 0; i < roots.size(); i++) {
                    List<String> code = new ArrayList<>();
                    for (Instruction instruction : roots.get(i).getBytecodeNode().getInstructions()) {
                        code.add(instruction.getName());
                        for (Instruction.Argument argument : instruction.getArguments()) {
                            if (argument.getKind() == Instruction.Argument.Kind.CONSTANT && argument.asConstant() instanceof BytecodeRootNode nested) {
                                roots.add(nested);
                            }
                        }
                    }
                    names.add(code);
                }
                return names;
            } finally {
                context.leave();
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Which loops are contified

    /** puzzle's shape: a do loop whose body calls return (non-tail) and a procedure the program defines. */
    private static final String PUZZLE = """
                    (define count 0)
                    (define (fits? i) (= i 7))
                    (define (trial n)
                      (call-with-current-continuation
                        (lambda (return)
                          (do ((i 0 (+ i 1))) ((> i n) (set! count (+ count 1)) #f)
                            (cond ((fits? i) (set! count (+ count 1)) (return #t)))))))
                    """;

    /** quicksort's shape: a do loop whose body calls return (non-tail) under unless. */
    private static final String QUICKSORT = """
                    (define (sorted? v)
                      (call-with-current-continuation
                        (lambda (return)
                          (do ((i 1 (+ i 1))) ((= i (vector-length v)) #t)
                            (unless (<= (vector-ref v (- i 1)) (vector-ref v i))
                              (return #f))))))
                    """;

    @Test
    public void aLoopThatCallsAnEscapeOnlyKIsContified() {
        // The procedure and the receiver: the loop is code of the receiver, not a root of its own.
        assertEquals(2, roots(PUZZLE + "(trial 10)", "trial").size());
        assertEquals(2, roots(QUICKSORT + "(sorted? (vector 1 2 3))", "sorted?").size());
        // In a nested loop too: both are code of the receiver.
        assertEquals(2, roots("""
                        (define (find-pair v w x)
                          (call/cc
                            (lambda (k)
                              (let outer ((i 0))
                                (if (< i (vector-length v))
                                    (let inner ((j 0))
                                      (if (< j (vector-length w))
                                          (begin
                                            (if (= (+ (vector-ref v i) (vector-ref w j)) x) (k (cons i j)))
                                            (inner (+ j 1)))
                                          (outer (+ i 1))))
                                    #f)))))
                        (find-pair (vector 1 2) (vector 3 4) 6)
                        """, "find-pair").size());
    }

    @Test
    public void otherLoopsThatCallKStayProcedures() {
        // k escapes (stored), so call/cc captures in full: k is a continuation like any other, and
        // the loop stays a procedure.
        List<List<String>> code = roots("""
                        (define saved #f)
                        (define (trial n)
                          (call/cc
                            (lambda (return)
                              (set! saved return)
                              (do ((i 0 (+ i 1))) ((> i n) #f)
                                (if (= i 3) (return #t))))))
                        (trial 5)
                        """, "trial");
        assertEquals(code.toString(), 3, code.size());
        // The loop also calls a procedure it was given: that call site is the one that goes
        // megamorphic (M7-02), so it stays a procedure.
        code = roots("""
                        (define (each-until g v)
                          (call/cc
                            (lambda (return)
                              (do ((i 0 (+ i 1))) ((= i (vector-length v)) #f)
                                (if (g (vector-ref v i)) (return i))))))
                        (each-until odd? (vector 2 3))
                        """, "each-until");
        assertEquals(code.toString(), 3, code.size());
    }

    // ------------------------------------------------------------------------------------------
    // Semantics

    @Test
    public void contifiedEscapeLoops() {
        check("(#t 1 #f 1)", PUZZLE + "(define a (trial 10)) (define b count) (define c (trial 5)) (write (list a b c (- count b)))");
        check("(#t #f #t #t)", QUICKSORT + "(write (list (sorted? (vector 1 2 3)) (sorted? (vector 1 3 2)) (sorted? (vector 4 4)) (sorted? (vector 5))))");
        check("((1 . 1) #f)", """
                        (define (find-pair v w x)
                          (call/cc
                            (lambda (k)
                              (let outer ((i 0))
                                (if (< i (vector-length v))
                                    (let inner ((j 0))
                                      (if (< j (vector-length w))
                                          (begin
                                            (if (= (+ (vector-ref v i) (vector-ref w j)) x) (k (cons i j)))
                                            (inner (+ j 1)))
                                          (outer (+ i 1))))
                                    #f)))))
                        (write (list (find-pair (vector 1 2) (vector 3 4) 6) (find-pair (vector 1) (vector 1) 3)))
                        """);
        check("(negative 2)", """
                        (define (f v)
                          (call/cc
                            (lambda (outer)
                              (+ 1 (call/cc
                                     (lambda (inner)
                                       (do ((i 0 (+ i 1))) ((= i (vector-length v)) 0)
                                         (if (negative? (vector-ref v i)) (outer 'negative))
                                         (if (zero? (vector-ref v i)) (inner i)))))))))
                        (write (list (f (vector 1 -1 0)) (f (vector 1 0 -1))))
                        """);
        // Escaping a loop leaves the dynamic-wind extents entered in it; many iterations run in
        // constant space; and k's values pass on, several of them too.
        check("(after 7 100000 (1 2))", """
                        (define log '())
                        (define (f)
                          (call/cc
                            (lambda (k)
                              (do ((i 0 (+ i 1))) (#f)
                                (dynamic-wind
                                  (lambda () #f)
                                  (lambda () (if (= i 7) (k i)))
                                  (lambda () (if (= i 7) (set! log (cons 'after log)))))))))
                        (define (g n)
                          (call/cc (lambda (k) (do ((i 0 (+ i 1))) (#f) (if (= i n) (k i))))))
                        (define (h)
                          (call-with-values
                            (lambda () (call/cc (lambda (k) (do ((i 0 (+ i 1))) (#f) (if (= i 2) (k 1 i))))))
                            list))
                        (let ((x (f))) (write (list (car log) x (g 100000) (h))))
                        """);
    }

    @Test
    public void reenteringAContinuationCapturedInAContifiedEscapeLoop() {
        // A full continuation captured in an iteration, re-entered after the loop escaped: the
        // loop goes on from that iteration, and k still escapes, from every copy.
        check("((0 1 2 3) (2 3) (2 3))", """
                        (define (test)
                          (define resume #f)
                          (define seen '())
                          (define results '())
                          (define (f)
                            (call/cc
                              (lambda (k)
                                (do ((i 0 (+ i 1))) (#f)
                                  (set! seen (cons i seen))
                                  (if (= i 3) (k (reverse seen)))
                                  (if (and (= i 1) (not resume)) (call/cc (lambda (c) (set! resume c))))))))
                          (set! results (cons (f) results))
                          (if (< (length results) 3) (begin (set! seen '()) (resume #f)))
                          (reverse results))
                        (write (test))
                        """);
    }
}
