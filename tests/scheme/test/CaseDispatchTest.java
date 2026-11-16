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

import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.Instruction;

import scheme.compiler.ProgramCompiler;
import scheme.runtime.GlobalCell;
import scheme.runtime.SchemeContext;
import scheme.runtime.value.SClosure;

/**
 * M8-05: {@code case} expands to {@code memv} of a quoted list of its clause's datums, and a call of
 * {@code memv} or {@code memq} (the primitives' own variables) with a quoted proper list compiles to
 * a comparison with each datum, with {@code eq?}'s operation, instead of a call of the primitive and
 * its loop. For {@code memv}, only datums that {@code eq?} compares as {@code eqv?} does qualify:
 * anything but a bignum, a non-integer exact rational or an exact complex number. The value is the
 * primitive's: the tail of the list whose car is the key, or #f.
 */
public class CaseDispatchTest {

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
        assertEquals(expr, expected, run("(import (scheme base) (scheme write) (scheme char))\n" + body));
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
    public void caseComparesWithEachDatumAndCallsNothing() {
        try (Context context = Context.create(SCHEME)) {
            List<String> code = instructions(context, """
                            (define (classify x)
                              (case x
                                ((less more) 'unequal)
                                ((equal) 'same)
                                ((#\\a 1 2.5 #t ()) 'other)
                                (else 'none)))
                            (classify 'less) (classify 'equal) (classify 1)
                            """, "classify");
            assertEquals(code.toString(), 0, count(code, "c.Call"));
            assertEquals(code.toString(), 8, count(code, "c.Eq"));
        }
    }

    @Test
    public void memvAndMemqOfAQuotedListCallNothing() {
        try (Context context = Context.create(SCHEME)) {
            List<String> code = instructions(context, """
                            (define (f x) (list (memv x '(a b c)) (memq x '(1 2))))
                            (f 'b)
                            """, "f");
            // Nothing is called but list, in tail position.
            assertEquals(code.toString(), 0, count(code, "c.Call"));
            assertEquals(code.toString(), 5, count(code, "c.Eq"));
        }
    }

    @Test
    public void otherMembersAreCalled() {
        try (Context context = Context.create(SCHEME)) {
            // A bignum datum, which eqv? compares by value and eq? doesn't; a list that isn't a
            // constant; member, which compares with equal?.
            List<String> code = instructions(context, """
                            (define l '(a b))
                            (define (f x)
                              (list (memv x '(a 100000000000000000000)) (memv x l) (member x '((a) b))))
                            (f 'a)
                            """, "f");
            assertEquals(code.toString(), 3, count(code, "c.Call"));
            assertFalse(code.toString(), code.stream().anyMatch(n -> n.startsWith("c.Eq")));
        }
    }

    @Test
    public void theValueIsThePrimitives() {
        check("", "((b c) #f (2 3) (#\\a) (1.5) #f (#f) (()) #f (b))",
                        "(list (memv 'b '(a b c)) (memv 'd '(a b c)) (memv 2 '(1 2 3)) (memv #\\a '(#\\b #\\a))" +
                                        " (memv 1.5 '(1 1.5)) (memv 1 '(1.0)) (memv #f '(#t #f)) (memv '() '(a ()))" +
                                        " (memv 'a '()) (memq 'b '(a b)))");
        // Numbers eq? compares by value (design.md D1), and the ones it doesn't, which memv does.
        check("", "((2.0) (100000000000000000000) (1/2) #f (1/2))",
                        "(list (memv (* 1.0 2) '(1 2.0)) (memv (expt 10 20) '(a 100000000000000000000)) (memv (/ 1 2) '(1/2))" +
                                        " (memq 'x '(1/2)) (memv (/ 2 4) '(1 1/2)))");
        // The key is evaluated once, in order.
        check("(define n 0) (define (next!) (set! n (+ n 1)) n)", "((2 3) 1)", "(let* ((r (memv (next!) '(0 1 2 3)))) (list (cdr r) n))");
    }

    @Test
    public void caseClauses() {
        String defs = """
                        (define (kind x)
                          (case x
                            ((a e i o u) 'vowel)
                            ((w y) => (lambda (c) (list c 'semivowel)))
                            ((#\\x 1 2) 'number-or-char)
                            (() 'never)
                            (else => (lambda (c) (list c 'other)))))
                        """;
        check(defs, "(vowel (y semivowel) number-or-char number-or-char (z other) (() other) (1.0 other))",
                        "(list (kind 'e) (kind 'y) (kind #\\x) (kind 2) (kind 'z) (kind '()) (kind 1.0))");
        // In tail position, and without else.
        check("(define (f x) (case (car x) ((1) 'one) ((2 3) 'two-or-three)))", "(one two-or-three #t)",
                        "(list (f '(1)) (f '(3)) (eq? (if #f #f) (f '(4))))");
    }

    @Test
    public void aProgramMayDefineItsOwnMemv() {
        assertEquals("(mine (b))", run("""
                        (import (except (scheme base) memv) (prefix (scheme base) base:) (scheme write))
                        (define (memv x l) 'mine)
                        (write (list (memv 'b '(a b)) (base:memv 'b '(a b))))
                        """));
        assertTrue(run("(import (scheme base) (scheme write)) (define (f x) (case x ((a) 1) (else 2))) (write (f 'a))").equals("1"));
    }
}
