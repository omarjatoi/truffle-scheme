package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import scheme.launcher.SchemeMain;

/**
 * M6-01: the {@code scheme} launcher (design.md D17). Programs with arguments, {@code -I}
 * libraries, scripted REPL sessions and exit statuses, first in this process
 * ({@link SchemeMain#run}), then through the real launcher in a child JVM, so the statuses reach
 * the process and the framework's options ({@code --help}, {@code --version}, {@code --engine.*},
 * {@code --log.*}) work.
 */
public class LauncherTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private record Result(int status, String out, String err) {
    }

    private Path file(String name, String text) throws IOException {
        Path path = folder.getRoot().toPath().resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
        return path;
    }

    private static Result run(String stdin, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = SchemeMain.run(args, new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)), out, err);
        return new Result(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static final String GREET_LIBRARY = """
                    (define-library (greet hello)
                      (export greet)
                      (import (scheme base))
                      (begin (define (greet name) (string-append "hello, " name))))
                    """;

    // --- programs ----------------------------------------------------------------------------

    @Test
    public void programSeesItsArgumentsAndItsExitStatusIsReturned() throws IOException {
        Path program = file("args.scm", """
                        (import (scheme base) (scheme write) (scheme process-context))
                        (write (cdr (command-line)))
                        (exit 4)
                        """);
        Result r = run("", program.toString(), "a", "b c", "-I", "--");
        assertEquals(r.err(), 4, r.status());
        assertEquals("(\"a\" \"b c\" \"-I\" \"--\")", r.out());
    }

    @Test
    public void commandLineStartsWithTheFile() throws IOException {
        Path program = file("first.scm", """
                        (import (scheme base) (scheme write) (scheme process-context))
                        (write (car (command-line)))
                        """);
        Result r = run("", "--", program.toString());
        assertEquals(r.err(), 0, r.status());
        assertEquals("\"" + program + "\"", r.out());
    }

    @Test
    public void dashIAddsALibraryDirectory() throws IOException {
        file("libs/greet/hello.sld", GREET_LIBRARY);
        file("more/extra/two.sld", """
                        (define-library (extra two) (export two) (import (scheme base)) (begin (define two 2)))
                        """);
        Path program = file("main.scm", """
                        (import (scheme base) (scheme write) (greet hello) (extra two))
                        (display (greet "world"))
                        (write two)
                        """);
        Path libs = folder.getRoot().toPath().resolve("libs");
        Path more = folder.getRoot().toPath().resolve("more");
        Result r = run("", "-I", libs.toString(), "-I" + more, program.toString());
        assertEquals(r.err(), 0, r.status());
        assertEquals("hello, world2", r.out());
        Result without = run("", "-I", libs.toString(), program.toString());
        assertEquals(SchemeMain.EXIT_UNCAUGHT_ERROR, without.status());
    }

    @Test
    public void emergencyExitStatusIsReturned() throws IOException {
        Path program = file("emergency.scm", """
                        (import (scheme base) (scheme process-context))
                        (dynamic-wind (lambda () #f) (lambda () (emergency-exit 6)) (lambda () (exit 1)))
                        """);
        assertEquals(6, run("", program.toString()).status());
    }

    @Test
    public void unreadableFileAndMalformedOptions() {
        Result missing = run("", folder.getRoot().toPath().resolve("missing.scm").toString());
        assertEquals(SchemeMain.EXIT_NO_INPUT, missing.status());
        assertTrue(missing.err(), missing.err().startsWith("ERROR: cannot read "));
        Result dashI = run("", "-I");
        assertEquals(SchemeMain.EXIT_USAGE, dashI.status());
        assertTrue(dashI.err(), dashI.err().contains("-I"));
    }

    // --- the REPL ----------------------------------------------------------------------------

    @Test
    public void replSessionWithMultiLineInputAnErrorAndMoreInput() {
        Result r = run("""
                        (define (square x)
                          (* x x))
                        (square 12)
                        (car '())
                        (list "a" #\\b
                              'c
                              1.5)
                        (values 1 "two")
                        (values)
                        (display "hi")
                        (newline)
                        (if #f #f)
                        (square (square 3))
                        """);
        assertEquals(r.err(), 0, r.status());
        assertEquals("144\n(\"a\" #\\b c 1.5)\n1\n\"two\"\nhi\n81\n", r.out());
        assertEquals(1, r.err().lines().count());
        assertTrue(r.err(), r.err().startsWith("ERROR: ") && r.err().contains("car"));
    }

    @Test
    public void replErrorShowsTheMessageAndIrritants() {
        Result r = run("""
                        (error "bad thing:" 42 "str" '(a b))
                        (raise 'oops)
                        'next
                        """);
        assertEquals(0, r.status());
        assertEquals("next\n", r.out());
        List<String> errors = r.err().lines().toList();
        assertEquals(r.err(), 2, errors.size());
        assertTrue(r.err(), errors.get(0).startsWith("ERROR: ") && errors.get(0).contains("bad thing:") && errors.get(0).contains("42") && errors.get(0).contains("\"str\"") &&
                        errors.get(0).contains("(a b)"));
        assertTrue(r.err(), errors.get(1).startsWith("ERROR: ") && errors.get(1).contains("oops"));
    }

    @Test
    public void replSyntaxErrorThenMoreInput() {
        Result r = run("""
                        )
                        (let ((x 1)) (+ x 1))
                        """);
        assertEquals(0, r.status());
        assertEquals("2\n", r.out());
        assertTrue(r.err(), r.err().startsWith("ERROR: "));
    }

    @Test
    public void replSeveralFormsOnALineAndOneFormOverSeveralLines() {
        Result r = run("""
                        (define x 1) (define y 2)
                        (+ x
                           ; a comment inside
                           y)
                        "a string with
                        a newline"
                        """);
        assertEquals(r.err(), 0, r.status());
        assertEquals("3\n\"a string with\\na newline\"\n", r.out());
    }

    @Test
    public void replExitStopsTheSessionWithItsStatus() {
        Result r = run("""
                        (display "before")
                        (exit 5)
                        (display "never")
                        """);
        assertEquals(5, r.status());
        assertEquals("before", r.out());
    }

    @Test
    public void replEndOfInputInsideADatumIsAnError() {
        Result r = run("""
                        (+ 1 2)
                        (+ 1
                        """);
        assertEquals(0, r.status());
        assertEquals("3\n", r.out());
        assertTrue(r.err(), r.err().startsWith("ERROR: "));
    }

    @Test
    public void replUsesTheLibraryPath() throws IOException {
        file("libs/greet/hello.sld", GREET_LIBRARY);
        Result r = run("""
                        (import (greet hello))
                        (greet "repl")
                        """, "-I", folder.getRoot().toPath().resolve("libs").toString());
        assertEquals(r.err(), 0, r.status());
        assertEquals("\"hello, repl\"\n", r.out());
    }

    // --- the real launcher, in a child JVM ---------------------------------------------------

    /** Runs {@code scheme args...} in a child JVM with this one's module path, with this stdin. */
    private Result launch(String stdin, String... args) throws IOException, InterruptedException {
        String java = ProcessHandle.current().info().command().orElseThrow();
        String modulePath = System.getProperty("jdk.module.path");
        assertNotNull("the tests run on the module path", modulePath);
        List<String> command = new ArrayList<>(List.of(java, "--module-path", modulePath, "--enable-native-access=org.graalvm.truffle", "--sun-misc-unsafe-memory-access=allow",
                        "-m", "scheme.launcher/scheme.launcher.SchemeMain"));
        command.addAll(List.of(args));
        Path out = folder.newFile().toPath();
        Path err = folder.newFile().toPath();
        Path in = folder.newFile().toPath();
        Files.writeString(in, stdin, StandardCharsets.UTF_8);
        Process process = new ProcessBuilder(command).directory(folder.getRoot()).redirectInput(in.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("scheme " + String.join(" ", args) + " timed out");
        }
        return new Result(process.exitValue(), Files.readString(out, StandardCharsets.UTF_8), Files.readString(err, StandardCharsets.UTF_8));
    }

    @Test
    public void processExitStatuses() throws IOException, InterruptedException {
        Path exits = file("exits.scm", """
                        (import (scheme base) (scheme write) (scheme process-context))
                        (write (cdr (command-line)))
                        (exit (string->number (cadr (command-line))))
                        """);
        Result r = launch("", exits.toString(), "3", "x");
        assertEquals(r.err(), 3, r.status());
        assertEquals("(\"3\" \"x\")", r.out());
        assertEquals(0, launch("", exits.toString(), "0").status());

        Path fails = file("fails.scm", "(car 1)\n");
        Result failed = launch("", fails.toString());
        assertEquals(SchemeMain.EXIT_UNCAUGHT_ERROR, failed.status());
        assertTrue(failed.err(), failed.err().startsWith("ERROR: "));

        Path finishes = file("finishes.scm", "(display \"done\")\n");
        Result finished = launch("", finishes.toString());
        assertEquals(finished.err(), 0, finished.status());
        assertEquals("done", finished.out());
    }

    @Test
    public void processReplSessionAndLibraryPath() throws IOException, InterruptedException {
        file("libs/greet/hello.sld", GREET_LIBRARY);
        Result r = launch("""
                        (import (greet hello))
                        (define (twice s)
                          (string-append s s))
                        (car 1)
                        (greet (twice "ab"))
                        (exit 9)
                        (display "never")
                        """, "-I", "libs");
        assertEquals(r.err(), 9, r.status());
        assertEquals("\"hello, abab\"\n", r.out());
        assertTrue(r.err(), r.err().startsWith("ERROR: "));

        Result ended = launch("(+ 1 2)\n");
        assertEquals(ended.err(), 0, ended.status());
        assertEquals("3\n", ended.out());
    }

    @Test
    public void processHelpVersionAndStandardOptions() throws IOException, InterruptedException {
        Result help = launch("", "--help");
        assertEquals(help.err(), 0, help.status());
        assertTrue(help.out(), help.out().startsWith("Usage: scheme "));
        assertTrue(help.out(), help.out().contains("-I <dir>"));
        assertTrue(help.out(), help.out().contains("--log.file="));

        Result version = launch("", "--version");
        assertEquals(version.err(), 0, version.status());
        assertTrue(version.out(), version.out().startsWith("R7RS Scheme "));

        Path program = file("standard.scm", "(display (+ 1 2))\n");
        Path log = folder.getRoot().toPath().resolve("engine.log");
        Result options = launch("", "--experimental-options", "--engine.Compilation=false", "--log.file=" + log, "--log.level=INFO", program.toString());
        assertEquals(options.err(), 0, options.status());
        assertEquals("3", options.out());

        Result showVersion = launch("", "--show-version", program.toString());
        assertEquals(showVersion.err(), 0, showVersion.status());
        assertTrue(showVersion.out(), showVersion.out().startsWith("R7RS Scheme ") && showVersion.out().endsWith("\n3"));

        Result unknown = launch("", "--no-such-option");
        assertTrue(unknown.out(), unknown.status() != 0);
        assertTrue(unknown.err(), unknown.err().contains("--no-such-option"));

        Result experimental = launch("", "--engine.Compilation=false", program.toString());
        assertTrue(experimental.out(), experimental.status() != 0);
        assertTrue(experimental.err(), experimental.err().contains("experimental"));
    }
}
