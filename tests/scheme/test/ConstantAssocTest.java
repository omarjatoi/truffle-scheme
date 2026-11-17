package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.Test;

import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.Instruction;

import scheme.compiler.ProgramCompiler;
import scheme.runtime.GlobalCell;
import scheme.runtime.SchemeContext;
import scheme.runtime.value.SClosure;

/**
 * M9-05: a call of {@code assq} or {@code assv} (the primitives' own variables) with a quoted proper
 * list of at most 16 pairs compiles, like M8-05's {@code memq} and {@code memv}
 * ({@link CaseDispatchTest}), to a comparison of the key with each entry's car by {@code eq?}, with
 * no call: one operation, {@code Assoc}. The value is the primitive's, the first entry whose car
 * matched, or #f. For {@code assv}, only
 * keys that {@code eq?} compares as {@code eqv?} does qualify: anything but a bignum, a non-integer
 * exact rational or an exact complex number.
 */
public class ConstantAssocTest {

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
        assertEquals(expr, expected, run("(import (scheme base) (scheme write))\n" + body));
        assertEquals(expr, expected, run(body));
    }

    /** The instructions of the procedure the interaction environment binds {@code name} to, after {@code program} ran. */
    private static List<String> instructions(Context context, String program, String name) {
        context.eval(SCHEME, program);
        context.enter();
        try {
            SchemeContext scheme = SchemeContext.get(null);
            GlobalCell cell = new ProgramCompiler().interactionCell(scheme, scheme.symbols.intern(name));
            BytecodeRootNode root = (BytecodeRootNode) ((SClosure) cell.valueOrNull()).callTarget.getRootNode();
            List<String> names = new ArrayList<>();
            for (Instruction instruction : root.getBytecodeNode().getInstructions()) {
                names.add(instruction.getName());
            }
            return names;
        } finally {
            context.leave();
        }
    }

    private static long count(List<String> names, String name) {
        return names.stream().filter(n -> n.startsWith(name)).count();
    }

    @Test
    public void assqAndAssvOfAQuotedAlistCallNothing() {
        try (Context context = Context.create(SCHEME)) {
            List<String> code = instructions(context, """
                            (define (f x) (list (assv x '((a . 1) (2 . b) (#\\c c))) (assq x '((x) (y 2)))))
                            (f 'a)
                            """, "f");
            // Nothing is called but list, in tail position: an operation for each.
            assertEquals(code.toString(), 0, count(code, "c.Call"));
            assertEquals(code.toString(), 2, count(code, "c.Assoc"));
        }
    }

    @Test
    public void otherAssocsAreCalled() {
        try (Context context = Context.create(SCHEME)) {
            // A bignum key, which eqv? compares by value and eq? doesn't; a ratio key; an entry that
            // isn't a pair (the primitive's error); an improper alist; a list that isn't a constant;
            // assoc, which compares with equal?; an alist past the limit.
            List<String> code = instructions(context, """
                            (define l '((a . 1)))
                            (define (f x)
                              (list (assv x '((a . 1) (100000000000000000000 . 2))) (assv x '((1/2 . 1)))
                                    (assq x '((a . 1) b)) (assq x '((a . 1) . b)) (assv x l) (assoc x '(((a)) (b)))
                                    (assq x '((a) (b) (c) (d) (e) (f) (g) (h) (i) (j) (k) (l) (m) (n) (o) (p) (q)))))
                            (f 'a)
                            """, "f");
            assertEquals(code.toString(), 7, count(code, "c.Call"));
            assertFalse(code.toString(), code.stream().anyMatch(n -> n.startsWith("c.Assoc") || n.startsWith("c.Eq")));
        }
    }

    @Test
    public void theValueIsThePrimitives() {
        check("", "((b . 2) #f (2 3) (#\\a) (1.5 x) #f (#f . f) (() . e) #f (b) (q))",
                        "(list (assv 'b '((a . 1) (b . 2))) (assv 'd '((a . 1))) (assv 2 '((1 1) (2 3))) (assv #\\a '((#\\b) (#\\a)))" +
                                        " (assv 1.5 '((1 y) (1.5 x))) (assv 1 '((1.0))) (assv #f '((#t . t) (#f . f))) (assv '() '((a) (() . e)))" +
                                        " (assv 'a '()) (assq 'b '((a) (b)))" +
                                        " (assq 'q '((a) (b) (c) (d) (e) (f) (g) (h) (i) (j) (k) (l) (m) (n) (o) (q))))");
        // The first entry that matches, and the entry itself, not a copy.
        check("(define (f x) (assq x '((a . 1) (a . 2))))", "((a . 1) #t)", "(list (f 'a) (eq? (f 'a) (f 'a)))");
        // Numbers eq? compares by value (design.md D1), and the ones it doesn't, which assv does.
        check("", "((2.0 . x) (100000000000000000000 . big) (1/2 . half) #f)",
                        "(list (assv (* 1.0 2) '((1 . y) (2.0 . x))) (assv (expt 10 20) '((a) (100000000000000000000 . big)))" +
                                        " (assv (/ 2 4) '((1) (1/2 . half))) (assq 'x '((1/2 . x))))");
        // The key is evaluated once, in order.
        check("(define n 0) (define (next!) (set! n (+ n 1)) n)", "((b) 1)", "(let* ((r (assv (next!) '((0 . z) (1 b) (2 . c))))) (list (cdr r) n))");
        // In tail position.
        check("(define (f x) (assq x '((a . 1) (b . 2))))", "((b . 2) #f)", "(list (f 'b) (f 'c))");
    }

    @Test
    public void anEntryThatIsNotAPairIsThePrimitivesError() {
        check("", "(a . 1)", "(assq 'a '((a . 1) b))");
        try {
            run("(import (scheme base) (scheme write)) (write (assq 'c '((a . 1) b)))");
            fail("expected an error");
        } catch (PolyglotException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("pair"));
        }
    }

    @Test
    public void aProgramMayDefineItsOwnAssq() {
        assertEquals("(mine (b))", run("""
                        (import (except (scheme base) assq) (prefix (scheme base) base:) (scheme write))
                        (define (assq x l) 'mine)
                        (write (list (assq 'b '((a) (b))) (base:assq 'b '((a) (b)))))
                        """));
    }
}
