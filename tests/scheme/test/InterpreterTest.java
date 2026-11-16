package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.Test;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.Instruction;

import scheme.compiler.ProgramCompiler;
import scheme.runtime.GlobalCell;
import scheme.runtime.SchemeContext;
import scheme.runtime.value.SClosure;

/**
 * M8-04: a procedure whose parameters are all required binds them with one instruction
 * ({@code BindArguments}): the arity check, and a store of each argument in its parameter's local,
 * boxed if the parameter is assigned, unboxed if it is always a fixnum.
 */
public class InterpreterTest {

    private static final String SCHEME = "scheme";

    private static String run(String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            context.eval(SCHEME, program);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** The message of the error {@code program} raises. */
    private static String error(String program) {
        try (Context context = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).err(new ByteArrayOutputStream()).build()) {
            context.eval(SCHEME, program);
        } catch (PolyglotException e) {
            assertTrue(e.isGuestException());
            return e.getMessage();
        }
        throw new AssertionError("no error from " + program);
    }

    private static BytecodeNode bytecode(RootCallTarget target) {
        return ((BytecodeRootNode) target.getRootNode()).getBytecodeNode();
    }

    /** The bytecode of the procedure the interaction environment binds {@code name} to. */
    private static BytecodeNode procedure(String name) {
        SchemeContext scheme = SchemeContext.get(null);
        GlobalCell cell = new ProgramCompiler().interactionCell(scheme, scheme.symbols.intern(name));
        return bytecode(((SClosure) cell.valueOrNull()).callTarget);
    }

    private static List<String> names(BytecodeNode bytecode) {
        List<String> names = new ArrayList<>();
        for (Instruction instruction : bytecode.getInstructions()) {
            names.add(instruction.getName());
        }
        return names;
    }

    @Test
    public void oneInstructionBindsTheParameters() {
        try (Context context = Context.create(SCHEME)) {
            context.eval(SCHEME, "(define (tak x y z) (if (not (< y x)) z (tak (tak (- x 1) y z) (tak (- y 1) z x) (tak (- z 1) x y)))) (tak 18 12 6)");
            context.enter();
            try {
                List<String> names = names(procedure("tak"));
                assertEquals(names.toString(), "c.BindArguments", names.get(0));
                // No load of an argument but the closure's (argument 0, for the self tail call).
                assertFalse(names.toString(), names.stream().anyMatch(n -> n.startsWith("load.argument$") || n.equals("c.CheckArity")));
                // The parameters are fixnums, kept unboxed in their locals.
                assertTrue(names.toString(), names.contains("load.local$Long$unboxed"));
            } finally {
                context.leave();
            }
        }
    }

    @Test
    public void boundParametersBehaveAsBefore() {
        // An assigned parameter is boxed, and each call gets a box of its own.
        assertEquals("(6 7 6 (2 . 1))", run("""
                        (define (counter n) (lambda () (set! n (+ n 1)) n))
                        (define a (counter 5))
                        (define b (counter 5))
                        (define (swap x y) (let ((t x)) (set! x y) (set! y t) (cons x y)))
                        (write (list (a) (a) (b) (swap 1 2)))"""));
        // A parameter that is a fixnum on one call and not on the next.
        assertEquals("(2 \"aa\" 2.5 (1 . 1))", run("""
                        (define (twice x) (if (string? x) (string-append x x) (if (pair? x) (cons (car x) (car x)) (+ x x))))
                        (write (list (twice 1) (twice "a") (twice 1.25) (twice '(1))))"""));
        // More parameters than the instruction's mask has bits, and a rest parameter.
        StringBuilder params = new StringBuilder();
        StringBuilder args = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            params.append(" a").append(i);
            args.append(' ').append(i);
        }
        assertEquals("(41 (2 3))", run("(define (f" + params + ") (+ a1 a40)) (define (g a . r) r) (write (list (f" + args + ") (g 1 2 3)))"));
        // Arity errors, with the expected and actual counts.
        assertTrue(error("(define (f a b) a) (f 1)").contains("expected 2, got 1"));
        assertTrue(error("(define (f a b) a) (f 1 2 3)").contains("expected 2, got 3"));
    }
}
