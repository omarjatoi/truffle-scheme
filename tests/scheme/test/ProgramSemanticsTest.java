package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import scheme.launcher.SchemeMain;

/**
 * M4-10: programs, bodies and top-level hygiene (design.md D14, D15, D16). A program (text whose
 * first form is {@code import}) sees only what it imports and defines; text without a leading
 * {@code import} runs in the interaction environment. Bodies may mix definitions and expressions.
 * Macro-introduced top-level definitions bind fresh cells.
 */
public class ProgramSemanticsTest {

    private static final String SCHEME = "scheme";

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private void file(String name, String text) throws IOException {
        Path path = folder.getRoot().toPath().resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    private Context.Builder context(ByteArrayOutputStream out) {
        return SchemeMain.newContext(List.of(folder.getRoot().getPath())).out(out).err(out);
    }

    /** Evaluates each text in turn in one context, and returns the output. */
    private String run(String... texts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = context(out).build()) {
            for (String text : texts) {
                context.eval(SCHEME, text);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Evaluates each text in turn in one context; the last one must raise. Returns output and message. */
    private String error(String... texts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = context(out).build()) {
            for (int i = 0; i < texts.length - 1; i++) {
                context.eval(SCHEME, texts[i]);
            }
            PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval(SCHEME, texts[texts.length - 1]));
            assertTrue(e.getMessage(), e.isGuestException());
            return out.toString(StandardCharsets.UTF_8) + "|" + e.getMessage();
        }
    }

    // ------------------------------------------------------------------------------------------
    // D15: a program's top level is its imports

    @Test
    public void programSeesOnlyItsImports() {
        // (only (scheme base) car): car is there, cdr isn't.
        String imports = "(import (only (scheme base) car quote) (only (scheme write) display))\n";
        assertEquals("1", run(imports + "(display (car '(1 2)))"));
        String e = error(imports + "(display (car '(1 2)))\n(display (cdr '(1 2)))");
        assertTrue(e, e.startsWith("1|") && e.contains("unbound variable: cdr"));
        // Everything R7RS puts in (scheme base) comes from it (port? used to leak in from elsewhere).
        assertEquals("#t", run("(import (scheme base) (scheme write))\n(display (port? (current-output-port)))"));
        // Core forms are bindings too: without (scheme base)'s if, if is just an unbound variable.
        assertTrue(error("(import (only (scheme base) car))\n(if 1 2 3)").contains("unbound variable: if"));
        assertTrue(error("(import (only (scheme write) display))\n(define x 1)").contains("unbound variable: define"));
        // Prefixed core forms work under their new names only: 'x is (quote x), and quote isn't bound.
        assertEquals("1", run("(import (prefix (scheme base) b:) (scheme write))\n(b:define x (b:if #t 1 2))\n(display x)"));
        assertTrue(error("(import (prefix (scheme base) b:) (scheme write))\n(display 'x)").contains("unbound variable: quote"));
    }

    @Test
    public void programsAndTheInteractionEnvironmentDontShare() {
        // A program doesn't see the interaction environment's definitions, nor its extra libraries.
        assertTrue(error("(define secret 1)", "(import (scheme base) (scheme write))\n(display secret)").contains("unbound variable: secret"));
        assertTrue(error("(import (scheme base) (scheme write))\n(display (sqrt 4))").contains("unbound variable: sqrt"));
        // Nor does the interaction environment see a program's definitions.
        assertTrue(error("(import (scheme base))\n(define leaked 1)", "leaked").contains("unbound variable: leaked"));
        // Each program has its own top level.
        assertTrue(error("(import (scheme base))\n(define mine 1)", "(import (scheme base) (scheme write))\n(display mine)").contains("unbound variable: mine"));
    }

    @Test
    public void programCannotAssignAnImport() {
        assertTrue(error("(import (scheme base))\n(set! car 1)").contains("set! of an imported variable"));
        // A definition shadows the import instead, for the whole program.
        assertEquals("(1 . 2)", run("(import (scheme base) (scheme write))\n(define (f x) (car x))\n(define (car x) x)\n(display (f '(1 . 2)))"));
        // ... and its own definitions can be assigned.
        assertEquals("2", run("(import (scheme base) (scheme write))\n(define n 1)\n(set! n (+ n 1))\n(display n)"));
    }

    @Test
    public void interactionEnvironmentUnchanged() {
        // Text without a leading import: every standard library, core forms by name, and
        // definitions that persist from one input to the next.
        assertEquals("1.5 1", run("(define x 1)", "(display (sqrt 2.25)) (display \" \") (display x)"));
        // A define-library first, then import: still the interaction environment.
        assertEquals("1 1.5", run("""
                        (define-library (test one) (import (scheme base)) (export one) (begin (define one 1)))
                        (import (test one))
                        (display one) (display " ") (display (sqrt 2.25))"""));
    }

    @Test
    public void evalAndEnvironmentsFromAProgram() {
        // environment gives exactly its import sets; the interaction environment persists.
        assertEquals("(1 9 unbound)", run("""
                        (import (scheme base) (scheme write) (scheme eval) (scheme repl))
                        (eval '(define z 9) (interaction-environment))
                        (write (list (eval '(if #t 1 2) (environment '(scheme base)))
                                     (eval 'z (interaction-environment))
                                     (guard (e (#t 'unbound)) (eval '(if #t 1 2) (environment '(only (scheme base) car))))))"""));
    }

    // ------------------------------------------------------------------------------------------
    // D16: macro-introduced top-level definitions are hygienic

    private static final String DEFINE_GETTER = """
                    (define-syntax define-getter
                      (syntax-rules ()
                        ((_ name value) (begin (define helper value) (define (name) helper)))))
                    (define-getter get-a 'a)
                    (define-getter get-b 'b)
                    """;

    @Test
    public void helperDefiningMacroExpansionsDontCollide() {
        String program = "(import (scheme base) (scheme write))\n" + DEFINE_GETTER;
        assertEquals("(a b)", run(program + "(display (list (get-a) (get-b)))"));
        // The helper is invisible to the program's own identifiers.
        assertTrue(error(program + "(display helper)").contains("unbound variable: helper"));
        // A program's own helper is a different variable.
        assertEquals("(mine a b)", run(program + "(define helper 'mine) (display (list helper (get-a) (get-b)))"));
        // The same in the interaction environment.
        assertEquals("(a b)", run(DEFINE_GETTER + "(display (list (get-a) (get-b)))"));
        assertTrue(error(DEFINE_GETTER + "(display helper)").contains("unbound variable: helper"));
        // define-values of no variables defines an introduced dummy each time.
        assertEquals("ok", run("(import (scheme base) (scheme write))\n(define-values () (values))\n(define-values () (values))\n(display 'ok)"));
    }

    @Test
    public void introducedDefinitionsInLibraries() throws IOException {
        // A library's macro, used in a program, defines and assigns its own counter each time; the
        // library also uses it itself, and exports helper by a name that doesn't refer to it.
        file("test/counters.sld", """
                        (define-library (test counters)
                          (import (scheme base))
                          (export define-counter tick helper)
                          (begin
                            (define-syntax define-counter
                              (syntax-rules ()
                                ((_ name) (begin (define helper 0)
                                                 (define (name) (set! helper (+ helper 1)) helper)))))
                            (define-counter tick)))
                        """);
        String program = """
                        (import (scheme base) (scheme write) (test counters))
                        (define-counter a)
                        (define-counter b)
                        (a) (a) (b) (tick)
                        """;
        assertEquals("(3 2 2)", run(program + "(display (list (a) (b) (tick)))"));
        assertTrue(error(program + "(display helper)").contains("unbound variable: helper"));
    }

    // ------------------------------------------------------------------------------------------
    // D14: bodies may mix definitions and expressions

    @Test
    public void mixedBodyRunsInOrder() {
        String program = """
                        (import (scheme base) (scheme write))
                        (define (f)
                          (display "a")
                          (define x (begin (display "b") 1))
                          (display "c")
                          (define (g) (+ x y))
                          (define y (begin (display "d") 2))
                          (display "e")
                          (g))
                        (display (f))
                        """;
        assertEquals("abcde3", run(program));
        // In let and lambda bodies, with macro-made definitions, and in the interaction environment.
        assertEquals("1(2 3)", run("(display (let () (display 1) (define-values (a b) (values 2 3)) (list a b)))"));
        assertEquals("12", run("((lambda () (display 1) (define-record-type point (make-point x) point? (x point-x)) (display (point-x (make-point 2)))))"));
        // letrec* semantics over the whole body: a closure made before a definition sees it.
        assertEquals("5", run("(define (h) (define (get) later) (display \"\") (define later 5) (get)) (display (h))"));
        // A body still needs an expression at the end.
        assertTrue(error("(define (k) 1 (define a 1))").contains("a body"));
    }
}
