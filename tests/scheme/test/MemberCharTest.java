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
 * M12-04: {@code memv} or {@code memq} of a quoted list of more than ConstantMember.LIMIT (16)
 * characters, which is what {@code case} makes of a clause of letters, compiles to one
 * {@code MemberChar} operation, a binary search of their code points, instead of a call of the
 * primitive (design.md D17, "The native launcher's steady state"). Its value is the primitive's.
 */
public class MemberCharTest {

    private static final String LETTERS = "#\\a #\\b #\\c #\\d #\\e #\\f #\\g #\\h #\\i #\\j #\\k #\\l #\\m #\\n #\\o #\\p #\\q #\\r #\\s #\\t #\\u #\\v #\\w #\\x #\\y #\\z";

    private static String write(String expr) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("scheme").out(out).build()) {
            context.eval("scheme", "(write " + expr + ")");
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** The instructions of the procedure the interaction environment binds {@code name} to, after {@code program} ran. */
    private static List<String> instructions(Context context, String program, String name) {
        context.eval("scheme", program);
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
    public void aLongListOfCharactersIsOneOperation() {
        try (Context context = Context.create("scheme")) {
            List<String> code = instructions(context, "(define (f c) (if (memv c '(" + LETTERS + ")) (case c ((" + LETTERS + " #\\_) 'letter) (else 'other)) (memq c '(" +
                            LETTERS + " #\\0))))\n(f #\\a)", "f");
            assertEquals(code.toString(), 0, count(code, "c.Call"));
            assertEquals(code.toString(), 3, count(code, "c.MemberChar"));
        }
    }

    /** Sixteen datums or fewer stay a chain of Eq operations; a list with anything but characters calls the primitive. */
    @Test
    public void otherListsAreNot() {
        try (Context context = Context.create("scheme")) {
            List<String> code = instructions(context, "(define (f c) (list (memv c '(#\\a #\\b)) (memv c '(" + LETTERS + " a))))\n(f #\\a)", "f");
            assertEquals(code.toString(), 0, count(code, "c.MemberChar"));
            assertEquals(code.toString(), 2, count(code, "c.Eq"));
        }
    }

    /**
     * The tail of the list at the key's first occurrence, whatever the list's order, or #f, for a
     * character that isn't in it and for any other key.
     */
    @Test
    public void theValueIsThePrimitives() {
        String list = "'(#\\z " + LETTERS + " #\\a #\\λ #\\0)";
        assertEquals("((#\\y #\\z #\\a #\\λ #\\0) (#\\a #\\b) #t (#\\λ #\\0) (#\\0) #f #f #f #f #f (#\\z #\\a #\\b))",
                        write("(let ((f (lambda (c) (memv c " + list + "))) (g (lambda (c) (memq c " + list + ")))) " +
                                        "(list (f #\\y) (let ((t (f #\\a))) (list (car t) (cadr t))) (eq? (f #\\a) (cdr (f #\\z))) (f #\\λ) (g #\\0) (f #\\A) " +
                                        "(f 97) (f 'a) (f \"a\") (g #\\space) (let ((t (g #\\z))) (list (car t) (cadr t) (caddr t)))))"));
        assertEquals("(letter letter digit other other)", write("(map (lambda (c) (case c ((" + LETTERS + ") 'letter) ((#\\0 #\\1 #\\2 #\\3 #\\4 #\\5 #\\6 #\\7 #\\8 #\\9) 'digit) " +
                        "(else 'other))) (list #\\a #\\q #\\7 #\\A 1))"));
    }
}
