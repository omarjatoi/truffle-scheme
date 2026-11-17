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
 * M3-04: libraries (R7RS §5.6, design.md D7): define-library, import sets, renamed exports,
 * include, cond-expand, the library path, and the standard libraries. Files are written by the
 * test host; the language reads them through the Env.
 */
public class LibraryTest {

    private static final String SCHEME = "scheme";

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private void file(String name, String text) throws IOException {
        Path path = folder.getRoot().toPath().resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    private String run(String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = SchemeMain.newContext(List.of(folder.getRoot().getPath())).out(out).build()) {
            context.eval(SCHEME, program);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Runs a program file in the folder, so includes are relative to it. */
    private String runFile(String name) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = SchemeMain.newContext(List.of(folder.getRoot().getPath())).out(out).build()) {
            context.eval(org.graalvm.polyglot.Source.newBuilder(SCHEME, folder.getRoot().toPath().resolve(name).toFile()).build());
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private String error(String program) {
        try (Context context = SchemeMain.newContext(List.of(folder.getRoot().getPath())).build()) {
            PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval(SCHEME, program));
            assertTrue(e.getMessage(), e.isGuestException());
            return e.getMessage();
        }
    }

    private static final String COUNTER = """
                    (define-library (test counter)
                      (import (scheme base) (scheme write))
                      (export next! (rename current value) twice)
                      (begin
                        (display "init ")
                        (define current 0)
                        (define (bump) (set! current (+ current 1)) current)
                        (define (next!) (bump))
                        (define-syntax twice (syntax-rules () ((_ e) (begin e e))))))
                    """;

    @Test
    public void instantiatedOnceOnFirstImport() {
        // Declaring doesn't instantiate; the first import does, and only the first.
        assertEquals("init 1 2", run(COUNTER + "(import (test counter)) (import (test counter)) (display (next!)) (display \" \") (display (next!))"));
        assertEquals("", run(COUNTER));
        // Two libraries importing it share one instance.
        assertEquals("init (1 2)", run(COUNTER + """
                        (define-library (test a) (import (scheme base) (test counter)) (export a) (begin (define (a) (next!))))
                        (define-library (test b) (import (scheme base) (test counter)) (export b) (begin (define (b) (next!))))
                        (import (test a) (test b))
                        (display (list (a) (b)))"""));
    }

    @Test
    public void renamedExportsAndExportedMacros() {
        // value is current, renamed; twice is a macro whose expansion refers to the library's
        // own bump through next!, which the importer can't see.
        assertEquals("init 0 2 2", run(COUNTER + """
                        (import (test counter))
                        (display value) (display " ") (twice (next!)) (display value) (display " ")
                        (define (bump) 'mine) (display value)"""));
        assertTrue(error(COUNTER + "(import (test counter)) current").contains("unbound variable: current"));
    }

    @Test
    public void importSets() {
        String lib = COUNTER;
        assertEquals("init 1", run(lib + "(import (only (test counter) next!)) (display (next!))"));
        assertEquals("init 1", run(lib + "(import (prefix (test counter) c:)) (display (c:next!))"));
        assertEquals("init 1", run(lib + "(import (rename (test counter) (next! advance))) (display (advance))"));
        assertEquals("init 1", run(lib + "(import (prefix (rename (only (test counter) next! value) (next! n)) c-)) (display (c-n))"));
        // A library sees only what it imports.
        assertEquals("(1 #f)", run(COUNTER + """
                        (define-library (test only)
                          (import (only (scheme base) define quote list) (except (test counter) twice) (scheme write))
                          (export probe)
                          (begin (define (probe) (list (next!) 'x))))
                        (import (test only))
                        (display ((lambda (p) (list (car p) (eq? (car (cdr p)) 'y))) (probe)))""").replace("init ", ""));
        assertTrue(error(lib + "(define-library (test bad) (import (only (test counter) nope)) (export) (begin))(import (test bad))").contains("import only: not exported"));
        assertTrue(error(lib + "(define-library (test bad) (import (except (test counter) twice) (scheme base)) (export t) (begin (define (t) (twice 1))))" +
                        "(import (test bad)) (t)").contains("unbound variable: twice"));
    }

    @Test
    public void importedVariablesCannotBeAssigned() {
        assertTrue(error("(set! car 1)").contains("set! of an imported variable"));
        assertTrue(error(COUNTER + "(import (test counter)) (set! value 5)").contains("set! of an imported variable"));
        // A definition of an imported name shadows it instead, for the whole program.
        assertEquals("(1 . 2)", run("(define (f x) (car x)) (define (car x) x) (display (f '(1 . 2)))"));
    }

    @Test
    public void condExpand() {
        assertEquals("r7rs", run("(cond-expand (r7rs (display 'r7rs)) (else (display 'other)))"));
        assertEquals("other", run("(cond-expand ((not r7rs) (display 'no)) (else (display 'other)))"));
        assertEquals("yes", run("(cond-expand ((and r7rs (or no-such-feature full-unicode) (library (scheme base))) (display 'yes)))"));
        assertEquals("no", run("(cond-expand ((library (no such)) (display 'yes)) ((and) (display 'no)))"));
        assertEquals("", run("(cond-expand ((or) (display 'yes)))"));
        // In a body, as an expression, and as a library declaration.
        assertEquals("3", run("(define (f) (cond-expand (r7rs (define x 3))) x) (display (f))"));
        assertEquals("4", run("(display (+ 1 (cond-expand (ratios 3) (else 0))))"));
        assertEquals("5", run("""
                        (define-library (test ce)
                          (cond-expand
                            ((library (scheme base)) (import (scheme base)))
                            (else (import (no such))))
                          (export five)
                          (begin (define five 5)))
                        (import (test ce)) (display five)"""));
    }

    @Test
    public void librariesOnThePathAndInclude() throws IOException {
        file("lib/util.sld", """
                        (define-library (lib util)
                          (import (scheme base))
                          (export square-it shout)
                          (include "util-body.scm")
                          (include-ci "util-ci.scm"))
                        """);
        file("lib/util-body.scm", "(define (square-it x) (* x x))");
        file("lib/util-ci.scm", "(define SHOUT 'Loud)");
        // A program sees only what it imports (design.md D15), so it imports what it displays with.
        assertEquals("(49 loud)", run("(import (scheme base) (scheme write) (lib util)) (display (list (square-it 7) shout))"));
        // include in a program, relative to the program file; include-library-declarations.
        file("prog/main.scm", "(define x 1) (include \"part.scm\") (display (list x y))");
        file("prog/part.scm", "(define y (+ x 1))");
        assertEquals("(1 2)", runFile("prog/main.scm"));
        file("decls.scm", "(export eight) (begin (define eight 8))");
        file("lib/decl.sld", "(define-library (lib decl) (import (scheme base)) (include-library-declarations \"../decls.scm\"))");
        assertEquals("8", run("(import (scheme write) (lib decl)) (display eight)"));
        assertTrue(error("(import (lib missing))").contains("library not found: (lib missing)"));
        assertTrue(error("(include \"nope.scm\")").contains("cannot find file nope.scm"));
    }

    @Test
    public void includeSearchesNextToTheIncludingFileThenTheLibraryPath() throws IOException {
        // M4-12: Larceny's libraries include files by their path from the library root (R7RS
        // §4.1.7 leaves the search to the implementation), and an included file includes files
        // next to itself.
        file("suite/tests/lib.sld", """
                        (define-library (suite tests lib)
                          (import (scheme base))
                          (export a b)
                          (include "suite/tests/parts/a.scm"))
                        """);
        file("suite/tests/parts/a.scm", "(define a 1) (include \"b.scm\")");
        file("suite/tests/parts/b.scm", "(define b 2)");
        assertEquals("(1 2)", run("(import (scheme base) (scheme write) (suite tests lib)) (display (list a b))"));
    }

    @Test
    public void errors() {
        assertTrue(error("(define-library (test loop) (import (test loop)) (export) (begin))(import (test loop))").contains("library imports itself"));
        assertTrue(error("(define-library (test bad) (frobnicate)) (import (test bad))").contains("invalid library declaration"));
        assertTrue(error("(define (f) (import (scheme base)) 1)").contains("only allowed at the top level"));
    }

    /**
     * R7RS §5.6.1: "an <identifier> names a single binding defined within or imported into the
     * library". An export of a name the library neither defines nor imports used to export an
     * unbound variable, and an importer got "unbound variable" only when it used it.
     */
    @Test
    public void exportingAnUndefinedIdentifierIsAnError() throws IOException {
        file("ex/undef.sld", "(define-library (ex undef) (import (scheme base)) (export nothere) (begin (define here 1)))");
        String e = error("(import (scheme base) (ex undef))");
        assertTrue(e, e.contains("exports nothere, which it neither defines nor imports"));
        file("ex/undef2.sld", "(define-library (ex undef2) (import (scheme base)) (export (rename nothere there)))");
        e = error("(import (scheme base) (ex undef2))");
        assertTrue(e, e.contains("exports nothere, which it neither defines nor imports"));
        // Defined, imported, a macro, or an imported keyword: all fine, with or without a body.
        file("ex/fine.sld", """
                        (define-library (ex fine)
                          (import (scheme base))
                          (export here car (rename m mac) if)
                          (begin (define here 1) (define-syntax m (syntax-rules () ((_) 3)))))
                        """);
        file("ex/fine2.sld", "(define-library (ex fine2) (import (scheme base)) (export cdr))");
        assertEquals("(1 2 3)", run("(import (scheme base) (scheme write) (ex fine) (ex fine2)) (write (list here (car (cdr '(1 2))) (if #t (mac) 0)))"));
    }

    /**
     * R7RS §5.6.1: each export spec names one binding by its external name, so two specs with the
     * same external name and different bindings make that name mean two things; §5.2: "In a program
     * or library declaration, it is an error to import the same identifier more than once with
     * different bindings ... However, a REPL should permit these actions." Both used to keep the
     * last one silently. Importing one binding twice (through two libraries, or twice from one) is
     * fine, and the interaction environment may rebind a name by importing it again.
     */
    @Test
    public void conflictingExportsAndImportsAreErrors() throws IOException {
        file("ex/dupexp.sld", "(define-library (ex dupexp) (import (scheme base)) (export a (rename b a)) (begin (define a 1) (define b 2)))");
        String e = error("(import (scheme base) (ex dupexp))");
        assertTrue(e, e.contains("exports a twice"));
        file("ex/mycar.sld", "(define-library (ex mycar) (import (scheme base)) (export car) (begin (define (car x) 'mine)))");
        e = error("(import (scheme base) (ex mycar))");
        assertTrue(e, e.contains("imports car twice, with different bindings"));
        e = error("(import (rename (scheme base) (cdr car)) (only (scheme base) car))");
        assertTrue(e, e.contains("imports car twice, with different bindings"));
        file("ex/user.sld", "(define-library (ex user) (import (scheme base) (ex mycar)) (export f) (begin (define (f) 1)))");
        e = error("(import (scheme base) (ex user))");
        assertTrue(e, e.contains("imports car twice, with different bindings"));
        // The same binding twice: through (scheme base) and (scheme r5rs), one library twice, or one binding under two names.
        assertEquals("1", run("(import (scheme base) (scheme r5rs) (scheme base) (only (scheme write) display)) (display (car '(1)))"));
        file("ex/same.sld", "(define-library (ex same) (import (scheme base)) (export car (rename car kar)) (begin))");
        assertEquals("(2 1)", run("(import (scheme base) (scheme write) (ex same) (rename (ex same) (kar car2))) (write (list (car2 '(2)) (kar '(1))))"));
        // Excluding the clash makes it fine.
        assertEquals("mine", run("(import (except (scheme base) car) (ex mycar) (only (scheme write) display)) (display (car 1))"));
        // The interaction environment (text without a leading import) permits it: the last import wins.
        assertEquals("mine", run("(display 0) (import (ex mycar)) (display (car 1))").substring(1));
    }

    @Test
    public void standardLibraries() {
        // Every standard library resolves, and shares cells: (scheme r5rs)'s car is (scheme base)'s.
        assertEquals("3", run("""
                        (define-library (test std)
                          (import (scheme base) (scheme case-lambda) (scheme char) (scheme complex) (scheme cxr)
                                  (scheme eval) (scheme file) (scheme inexact) (scheme lazy) (scheme load)
                                  (scheme process-context) (scheme read) (scheme repl) (scheme time) (scheme write)
                                  (scheme r5rs))
                          (export f)
                          (begin (define (f) (caddr (list 1 2 3)))))
                        (import (test std)) (display (f))"""));
        // A program with explicit imports (R7RS §5.1) still works.
        assertEquals("ok", run("(import (scheme base) (scheme write)) (display 'ok)"));
    }

    /**
     * R7RS erratum 22: "In Appendix A, the (scheme r5rs) library should export syntax-rules,
     * else, ..., => and _." Without them, a program on (scheme r5rs) alone couldn't write a macro.
     */
    @Test
    public void r5rsExportsSyntaxRulesAndItsAuxiliarySyntax() {
        assertEquals("(ok 3 other)", run("""
                        (import (only (scheme r5rs) define-syntax syntax-rules else => _ ... quote list cond case lambda car cdr)
                                (only (scheme write) write))
                        (define-syntax m
                          (syntax-rules (else =>)
                            ((_ else => _ ...) 'ok)
                            ((_ x ...) 'other)))
                        (write (list (m else => 1 2)
                                     (cond (#f 1) ((list 1 2 3) => (lambda (l) (car (cdr (cdr l))))))
                                     (case 1 ((2) 'two) (else 'other))))"""));
    }
}
