package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.SourceSection;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import scheme.launcher.SchemeMain;

/**
 * M4-09: {@code syntax-error} (R7RS §4.3.3) and {@code load} (R7RS §6.13.1, design.md D5, D6).
 * Expanding {@code syntax-error} is a syntax error carrying its message and irritants: a parse
 * error of the program, or a raisable error object inside {@code eval} and {@code load}.
 * {@code load} compiles a file in an environment and tail-calls the result.
 */
public class SyntaxErrorLoadTest {

    private static final String SCHEME = "scheme";

    /** R7RS §4.3.3's example. */
    private static final String SIMPLE_LET = """
                    (define-syntax simple-let
                      (syntax-rules ()
                        ((_ (head ... ((x . y) val) . tail)
                            body1 body2 ...)
                         (syntax-error
                          "expected an identifier but got"
                          (x . y)))
                        ((_ ((name val) ...) body1 body2 ...)
                         ((lambda (name ...) body1 body2 ...)
                          val ...))))
                    """;

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private String run(String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = SchemeMain.newContext(List.of()).out(out).build()) {
            context.eval(SCHEME, program);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static PolyglotException error(String program) {
        try (Context context = Context.create(SCHEME)) {
            return assertThrows(PolyglotException.class, () -> context.eval(org.graalvm.polyglot.Source.newBuilder(SCHEME, program, "prog.scm").buildLiteral()));
        }
    }

    /** A file in the temporary folder with this text; returns its path as a Scheme string literal. */
    private String file(String name, String text) throws IOException {
        Path path = folder.getRoot().toPath().resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
        return "\"" + path.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ------------------------------------------------------------------------------------------
    // syntax-error

    @Test
    public void simpleLetExpandsValidUses() {
        assertEquals("3", run(SIMPLE_LET + "(write (simple-let ((a 1) (b 2)) (+ a b)))"));
    }

    @Test
    public void simpleLetReportsItsSyntaxError() {
        PolyglotException e = error(SIMPLE_LET + "(display \"before\")\n(simple-let (((a . b) 1)) a)\n");
        assertTrue(e.getMessage(), e.isSyntaxError());
        assertTrue(e.getMessage(), e.getMessage().contains("expected an identifier but got"));
        assertTrue(e.getMessage(), e.getMessage().contains("(a . b)"));
        // The error names the use that expanded into syntax-error (design.md D13).
        SourceSection at = e.getSourceLocation();
        assertTrue(e.getMessage(), at != null && at.getSource().getName().equals("prog.scm") && at.getStartLine() == 12);
    }

    @Test
    public void syntaxErrorAnywhereIsAnError() {
        // In expression position, in a body, and at the top level; a template that is never used is fine.
        assertTrue(error("(syntax-error \"top\" 1 2)").isSyntaxError());
        assertTrue(error("(define (f) (syntax-error \"in a body\"))").isSyntaxError());
        assertTrue(error("(display (+ 1 (syntax-error \"in an expression\" x)))").isSyntaxError());
        assertTrue(error("(syntax-error not-a-string)").isSyntaxError());
        assertTrue(error("(syntax-error)").isSyntaxError());
        assertEquals("ok", run("(define-syntax m (syntax-rules () ((_ x) 'ok) ((_) (syntax-error \"no\")))) (display (m 1))"));
    }

    @Test
    public void syntaxErrorInEvalIsRaisedWithMessageAndIrritants() {
        assertEquals("(\"boom\" (1 (a b)))", run("""
                        (write (guard (e ((error-object? e) (list (error-object-message e) (error-object-irritants e))))
                                 (eval '(syntax-error "boom" 1 (a b)) (environment '(scheme base)))))"""));
        assertEquals("(\"expected an identifier but got\" ((a . b)))", run(SIMPLE_LET + """
                        (write (guard (e ((error-object? e) (list (error-object-message e) (error-object-irritants e))))
                                 (eval '(simple-let (((a . b) 1)) a) (interaction-environment))))"""));
    }

    @Test
    public void syntaxErrorCanBeShadowed() {
        assertEquals("(1 2)", run("(let ((syntax-error list)) (write (syntax-error 1 2)))"));
    }

    // ------------------------------------------------------------------------------------------
    // load

    @Test
    public void loadIntoTheInteractionEnvironment() throws IOException {
        String path = file("defs.scm", """
                        (define loaded-x 42)
                        (define-syntax twice (syntax-rules () ((_ e) (begin e e))))
                        (display "loading ")
                        """);
        // The default environment is the interaction environment; definitions and macros stay there.
        assertEquals("loading 42 ab ab ", run("(load " + path + ")" + """
                        (display loaded-x) (display " ")
                        (eval '(twice (display "ab ")) (interaction-environment))"""));
        assertEquals("loading 43", run("(load " + path + " (interaction-environment)) (display (+ loaded-x 1))"));
    }

    @Test
    public void loadIntoAnEnvironment() throws IOException {
        String path = file("exprs.scm", """
                        (write (map (lambda (x) (* x x)) '(1 2 3)))
                        (newline)
                        (write (let loop ((i 0) (acc 0)) (if (> i 4) acc (loop (+ i 1) (+ acc i)))))
                        """);
        assertEquals("(1 4 9)\n10", run("(load " + path + " (environment '(scheme base) '(scheme write)))"));
        // An environment made by environment is immutable: a definition in the file is an error.
        String defs = file("defs.scm", "(define y 1)\n");
        assertEquals("caught", run("(display (guard (e ((error-object? e) 'caught)) (load " + defs + " (environment '(scheme base)))))"));
        // Only the environment's bindings are visible.
        String uses = file("uses.scm", "(display \"x\")\n");
        assertEquals("caught", run("(display (guard (e ((error-object? e) 'caught)) (load " + uses + " (environment '(scheme base)))))"));
    }

    @Test
    public void loadErrorsAreRaised() throws IOException {
        String missing = "\"" + folder.getRoot().toPath().resolve("missing.scm") + "\"";
        assertEquals("file-error", run("(display (guard (e ((file-error? e) 'file-error)) (load " + missing + ")))"));
        assertEquals("caught", run("(display (guard (e ((error-object? e) 'caught)) (load 'not-a-string)))"));
        assertEquals("caught", run("(display (guard (e ((error-object? e) 'caught)) (load " + file("ok.scm", "1") + " 'not-an-environment)))"));
        // A syntax error in the file is an error object that names the file, not a parse error of the program.
        String bad = file("bad.scm", "(define z 1)\n(if)\n");
        assertTrue(run("(display (guard (e ((error-object? e) (error-object-message e))) (load " + bad + ")))").contains("bad.scm"));
        assertEquals("(\"nope\" (1))", run("(write (guard (e ((error-object? e) (list (error-object-message e) (error-object-irritants e)))) (load " +
                        file("se.scm", "(syntax-error \"nope\" 1)") + ")))"));
        String unreadable = file("unreadable.scm", "(display 1)\n(display (+ 1 2\n");
        assertEquals("caught", run("(display (guard (e ((error-object? e) 'caught)) (load " + unreadable + ")))"));
    }

    @Test
    public void loadedCodeHasItsFilesLocations() throws IOException {
        // Non-tail calls, so that their frames are on the stack when vector-ref fails.
        String path = file("fails.scm", "(define (f v)\n  (+ 1 (vector-ref v 5)))\n(display (f (vector 1)))\n(newline)\n");
        try (Context context = SchemeMain.newContext(List.of()).build()) {
            PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval(SCHEME, "(load " + path + ")"));
            List<String> frames = new ArrayList<>();
            for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
                SourceSection s = frame.getSourceLocation();
                if (frame.isGuestFrame() && s != null) {
                    frames.add(s.getSource().getName() + ":" + s.getStartLine());
                }
            }
            assertTrue(frames.toString(), frames.contains("fails.scm:2"));
            assertTrue(frames.toString(), frames.contains("fails.scm:3"));
        }
    }

    @Test
    public void loadIncludesRelativeToTheLoadedFile() throws IOException {
        file("sub/part.scm", "(display \"part\")\n");
        String path = file("sub/main.scm", "(include \"part.scm\")\n");
        assertEquals("part", run("(load " + path + ")"));
    }

    @Test
    public void loadReturnsTheLastValueAndNests() throws IOException {
        // The file's last form is in load's tail position (design.md D5), so a file can load itself.
        String path = file("count.scm", "(set! n (- n 1))\n(if (> n 0) (load file) 'done)\n");
        assertEquals("done", run("(define n 500) (define file " + path + ") (display (load file))"));
    }

    @Test
    public void continuationsReenterLoadedCode() throws IOException {
        String path = file("k.scm", "(define r (+ 1 (call/cc (lambda (c) (set! k c) 0))))\n(set! n (+ n 1))\n");
        assertEquals("(3 3)", run("(define k #f) (define n 0) (load " + path + ") (if (< n 3) (k n)) (write (list n r))"));
    }
}
