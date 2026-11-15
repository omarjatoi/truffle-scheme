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
        assertEquals("(49 loud)", run("(import (lib util)) (display (list (square-it 7) shout))"));
        // include in a program, relative to the program file; include-library-declarations.
        file("prog/main.scm", "(define x 1) (include \"part.scm\") (display (list x y))");
        file("prog/part.scm", "(define y (+ x 1))");
        assertEquals("(1 2)", runFile("prog/main.scm"));
        file("decls.scm", "(export eight) (begin (define eight 8))");
        file("lib/decl.sld", "(define-library (lib decl) (import (scheme base)) (include-library-declarations \"../decls.scm\"))");
        assertEquals("8", run("(import (lib decl)) (display eight)"));
        assertTrue(error("(import (lib missing))").contains("library not found: (lib missing)"));
        assertTrue(error("(include \"nope.scm\")").contains("cannot find file nope.scm"));
    }

    @Test
    public void errors() {
        assertTrue(error("(define-library (test loop) (import (test loop)) (export) (begin))(import (test loop))").contains("library imports itself"));
        assertTrue(error("(define-library (test bad) (frobnicate)) (import (test bad))").contains("invalid library declaration"));
        assertTrue(error("(define (f) (import (scheme base)) 1)").contains("only allowed at the top level"));
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
}
