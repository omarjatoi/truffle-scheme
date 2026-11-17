package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.junit.Test;

import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.Instruction;

import scheme.compiler.ProgramCompiler;
import scheme.runtime.GlobalCell;
import scheme.runtime.SchemeContext;
import scheme.runtime.value.SClosure;

/**
 * M8-04: a loop whose body is an {@code if} with the loop's jumps in one branch only loops on the
 * {@code if}'s test ({@code LoopTest}), with the other branch after the loop; and an {@code if}
 * whose test is a {@code not} branches on the {@code not}'s operand.
 */
public class LoopTestTest {

    private static final String SCHEME = "scheme";

    private static String run(String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            context.eval(SCHEME, program);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** The instruction names of the procedure the interaction environment binds {@code name} to, once {@code program} has run. */
    private static List<String> instructions(String program, String name) {
        try (Context context = Context.create(SCHEME)) {
            context.eval(SCHEME, program);
            context.enter();
            try {
                SchemeContext scheme = SchemeContext.get(null);
                GlobalCell cell = new ProgramCompiler().interactionCell(scheme, scheme.symbols.intern(name));
                BytecodeNode bytecode = ((BytecodeRootNode) ((SClosure) cell.valueOrNull()).callTarget.getRootNode()).getBytecodeNode();
                List<String> names = new ArrayList<>();
                for (Instruction instruction : bytecode.getInstructions()) {
                    names.add(instruction.getName());
                }
                return names;
            } finally {
                context.leave();
            }
        }
    }

    private static long count(List<String> names, String prefix) {
        return names.stream().filter(n -> n.startsWith(prefix)).count();
    }

    @Test
    public void aSelfTailCallingProcedureLoopsOnItsTest() {
        List<String> names = instructions("(define (tak x y z) (if (not (< y x)) z (tak (tak (- x 1) y z) (tak (- y 1) z x) (tak (- z 1) x y)))) (tak 18 12 6)", "tak");
        assertEquals(names.toString(), 1, count(names, "c.LoopTest"));
        assertFalse(names.toString(), names.stream().anyMatch(n -> n.startsWith("c.LoopHead") || n.startsWith("c.Not")));
        // The test of the loop's condition is the comparison's boolean, unboxed.
        assertTrue(names.toString(), names.contains("c.LoopTest$Boolean$unboxed"));
    }

    @Test
    public void namedLetAndDoLoopOnTheirTests() {
        String program = """
                        (define (sum n) (let loop ((i 0) (acc 0)) (if (< i n) (loop (+ i 1) (+ acc i)) acc)))
                        (define (count-down n) (do ((i n (- i 1)) (k 0 (+ k 1))) ((zero? i) k)))
                        (sum 100) (count-down 100)""";
        for (String name : List.of("sum", "count-down")) {
            List<String> names = instructions(program, name);
            assertEquals(name + names, 1, count(names, "c.LoopTest"));
            assertEquals(name + names, 0, count(names, "c.LoopHead"));
        }
    }

    @Test
    public void aLoopWithJumpsInBothBranchesKeepsItsHead() {
        List<String> names = instructions("(define (f i acc) (if (= i 0) acc (if (even? i) (f (- i 1) (+ acc 1)) (f (- i 1) acc)))) (f 10 0)", "f");
        // The outer if has jumps in one branch: it is the loop's test; the inner one is an if.
        assertEquals(names.toString(), 1, count(names, "c.LoopTest"));
        List<String> both = instructions("(define (g i) (if (even? i) (if (= i 0) 0 (g (- i 1))) (g (- i 1)))) (g 10)", "g");
        assertEquals(both.toString(), 0, count(both, "c.LoopTest"));
        assertEquals(both.toString(), 1, count(both, "c.LoopHead"));
    }

    @Test
    public void rotatedLoopsComputeAsBefore() {
        assertEquals("(7 4950 100 5 #t 6 (3 2 1) 10)", run("""
                        (define (tak x y z) (if (not (< y x)) z (tak (tak (- x 1) y z) (tak (- y 1) z x) (tak (- z 1) x y))))
                        (define (sum n) (let loop ((i 0) (acc 0)) (if (< i n) (loop (+ i 1) (+ acc i)) acc)))
                        (define (count-down n) (do ((i n (- i 1)) (k 0 (+ k 1))) ((zero? i) k)))
                        ;; The jumps in the false branch, the test not a boolean.
                        (define (len l n) (if (memv (car l) '(stop end)) n (len (cdr l) (+ n 1))))
                        ;; Two nots.
                        (define (find-pair l) (if (not (not (and (pair? l) (pair? (car l))))) #t (find-pair (cdr l))))
                        (define (f i acc) (if (= i 0) acc (if (even? i) (f (- i 1) (+ acc 1)) (f (- i 1) acc))))
                        (define (rev l acc) (if (null? l) acc (rev (cdr l) (cons (car l) acc))))
                        (define (spin i) (cond ((> i 9) i) (else (spin (+ i 1)))))
                        (write (list (tak 18 12 6) (sum 100) (count-down 100) (len '(a b c d e stop) 0)
                                     (find-pair '(1 2 (3))) (f 12 0) (rev '(1 2 3) '()) (spin 0)))"""));
    }

    @Test
    public void aTailCallOfAnotherProcedureFromTheLoopingBranchReturnsIt() {
        // f's tail call has as many arguments as f has parameters: a jump only if it calls f itself.
        assertEquals("(done 3)", run("""
                        (define (g n) (list 'done n))
                        (define (f n) (if (< n 3) (f (+ n 1)) n))
                        (define (h n) (if (< n 3) (g 3) n))
                        (write (h 0))"""));
        // The procedure the variable holds changes while the loop runs.
        assertEquals("(other 3)", run("""
                        (define (f n) (if (< n 5) (begin (if (= n 2) (set! f (lambda (m) (list 'other m)))) (f (+ n 1))) n))
                        (define g f)
                        (write (g 0))"""));
    }

    @Test
    public void notBranchesOnItsOperand() {
        assertEquals("(b a a b b a)", run("""
                        (define (t x) (if (not x) 'a 'b))
                        (define (tt x) (if (not (not x)) 'b 'a))
                        (write (list (t 1) (t #f) (tt #f) (tt '()) (if (not 0) 'a 'b) (if (not (eq? 1 2)) 'a 'b)))"""));
        // In value position too, and a not that isn't the primitive's.
        assertEquals("(no yes 1)", run("""
                        (define (v x) (let ((r (if (not x) 'yes 'no))) r))
                        (define (w not) (if (not 0) 1 2))
                        (write (list (v 5) (v #f) (w (lambda (x) x))))"""));
    }

    @Test
    public void aContinuationReentersARotatedLoop() {
        // The continuation captured in the loop's body is resumed after the loop has ended: the
        // loop's test runs again, from the frame as it was.
        assertEquals("(0 1 2 3 3 done)", run("""
                        (define k #f)
                        (define out '())
                        (define resumed #f)
                        (let loop ((i 0))
                          (if (< i 4)
                              (begin
                                (if (= i 3) (call/cc (lambda (c) (set! k c))))
                                (set! out (cons i out))
                                (loop (+ i 1)))
                              (set! out (cons 'done out))))
                        (if (not resumed)
                            (begin (set! resumed #t) (set! out (cdr out)) (k #f)))
                        (write (reverse out))"""));
    }
}
