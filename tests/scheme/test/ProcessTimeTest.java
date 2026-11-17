package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.Test;

import scheme.launcher.SchemeMain;

/**
 * M4-08: the process context and time area (R7RS §6.14): {@code exit} and {@code emergency-exit}
 * (design.md D9, an EXIT through interop, D13), the command line and environment variables (through
 * the Env, D10), time, {@code features}, {@code file-exists?} and {@code delete-file}.
 */
public class ProcessTimeTest {

    private static final String PRELUDE = "(import (scheme base) (scheme write) (scheme process-context) (scheme time) (scheme file))\n";

    private record Result(Integer exitStatus, String out) {
    }

    private static Result eval(Context.Builder builder, String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Integer status = null;
        // Closing an exited context reports the exit again, so the close is inside the try.
        try (Context context = builder.out(out).build()) {
            context.eval("scheme", PRELUDE + program);
        } catch (PolyglotException e) {
            if (!e.isExit()) {
                throw e;
            }
            status = e.getExitStatus();
        }
        return new Result(status, out.toString(StandardCharsets.UTF_8));
    }

    private static Result run(String program) {
        return eval(Context.newBuilder("scheme"), program);
    }

    private static String output(String program) {
        Result r = run(program);
        assertEquals("program exited: " + r.out(), null, r.exitStatus());
        return r.out();
    }

    @Test
    public void exitStatuses() {
        assertEquals(Integer.valueOf(0), run("(exit)").exitStatus());
        assertEquals(Integer.valueOf(0), run("(exit #t)").exitStatus());
        assertEquals(Integer.valueOf(1), run("(exit #f)").exitStatus());
        assertEquals(Integer.valueOf(7), run("(exit 7)").exitStatus());
        assertEquals(Integer.valueOf(1), run("(exit 'other)").exitStatus());
        assertEquals(Integer.valueOf(5), run("(emergency-exit 5)").exitStatus());
        assertEquals(Integer.valueOf(0), run("(emergency-exit)").exitStatus());
        assertEquals(Integer.valueOf(1), run("(emergency-exit #f)").exitStatus());
    }

    /**
     * An exact integer is the status as is from 0 to 255, what a process status holds; any other
     * is a failure, 1, so that it can't wrap to 0 (256) or to another status (design.md D9).
     */
    @Test
    public void exitStatusesOutsideAProcessStatusAreFailures() {
        assertEquals(Integer.valueOf(255), run("(exit 255)").exitStatus());
        assertEquals(Integer.valueOf(1), run("(exit 256)").exitStatus());
        assertEquals(Integer.valueOf(1), run("(exit 512)").exitStatus());
        assertEquals(Integer.valueOf(1), run("(exit 259)").exitStatus());
        assertEquals(Integer.valueOf(1), run("(exit -1)").exitStatus());
        assertEquals(Integer.valueOf(1), run("(exit -256)").exitStatus());
        assertEquals(Integer.valueOf(1), run("(exit 4294967296)").exitStatus());
        assertEquals(Integer.valueOf(1), run("(exit (expt 2 64))").exitStatus());
        assertEquals(Integer.valueOf(1), run("(exit (- (expt 2 64)))").exitStatus());
        assertEquals(Integer.valueOf(1), run("(exit 2.0)").exitStatus());
        assertEquals(Integer.valueOf(1), run("(emergency-exit 256)").exitStatus());
        assertEquals(Integer.valueOf(1), run("(emergency-exit -1)").exitStatus());
        assertEquals(Integer.valueOf(200), run("(emergency-exit 200)").exitStatus());
    }

    @Test
    public void exitIsAnExitThroughInterop() {
        try (Context context = Context.newBuilder("scheme").out(new ByteArrayOutputStream()).build()) {
            context.eval("scheme", PRELUDE + "(exit 3)");
            fail("exit returned");
        } catch (PolyglotException e) {
            assertTrue(e.isExit());
            assertEquals(3, e.getExitStatus());
        }
    }

    @Test
    public void exitStopsTheProgram() {
        Result r = run("(display \"before\") (exit 2) (display \"after\")");
        assertEquals(Integer.valueOf(2), r.exitStatus());
        assertEquals("before", r.out());
    }

    @Test
    public void exitRunsTheAfterThunksInnermostFirst() {
        Result r = run("""
                        (dynamic-wind
                          (lambda () (display "[a"))
                          (lambda ()
                            (dynamic-wind
                              (lambda () (display "[b"))
                              (lambda () (exit 4))
                              (lambda () (display "b]"))))
                          (lambda () (display "a]")))""");
        assertEquals(Integer.valueOf(4), r.exitStatus());
        assertEquals("[a[bb]a]", r.out());
    }

    @Test
    public void exitFromAHandlerAndAParameterizeStillUnwinds() {
        Result r = run("""
                        (define p (make-parameter 1))
                        (dynamic-wind
                          (lambda () #f)
                          (lambda ()
                            (parameterize ((p 2))
                              (with-exception-handler
                                (lambda (e) (exit 6))
                                (lambda () (raise 'boom)))))
                          (lambda () (display (p))))""");
        assertEquals(Integer.valueOf(6), r.exitStatus());
        assertEquals("1", r.out());
    }

    @Test
    public void emergencyExitSkipsTheAfterThunks() {
        Result r = run("""
                        (dynamic-wind
                          (lambda () (display "in "))
                          (lambda () (emergency-exit 2))
                          (lambda () (display "out")))""");
        assertEquals(Integer.valueOf(2), r.exitStatus());
        assertEquals("in ", r.out());
    }

    @Test
    public void environmentVariablesComeFromTheEnv() {
        Context.Builder builder = Context.newBuilder("scheme").environment("TRUFFLE_SCHEME_TEST", "a=b");
        assertEquals("\"a=b\" #f #t", eval(builder, """
                        (write (get-environment-variable "TRUFFLE_SCHEME_TEST"))
                        (display " ")
                        (write (get-environment-variable "TRUFFLE_SCHEME_NO_SUCH_VARIABLE"))
                        (display " ")
                        (write (let loop ((l (get-environment-variables)))
                                   (cond ((null? l) #f) ((equal? (car l) '("TRUFFLE_SCHEME_TEST" . "a=b")) #t) (else (loop (cdr l))))))""").out());
    }

    @Test
    public void commandLineIsTheApplicationArguments() {
        Context.Builder builder = Context.newBuilder("scheme").arguments("scheme", new String[]{"prog.scm", "x", "y"});
        assertEquals("(\"prog.scm\" \"x\" \"y\")", eval(builder, "(write (command-line))").out());
        assertEquals("()", output("(write (command-line))"));
    }

    @Test
    public void time() {
        assertEquals("#t #t #t #t #t", output("""
                        (define s (current-second))
                        (define j (current-jiffy))
                        (display (list (inexact? s) (> s 1.7e9) (exact-integer? j) (exact-integer? (jiffies-per-second)) (> (jiffies-per-second) 0)))
                        """).replaceAll("[()]", ""));
        assertEquals("#t", output("""
                        (define a (current-jiffy))
                        (define b (current-jiffy))
                        (write (<= a b))"""));
        double before = System.currentTimeMillis() / 1000.0;
        double second = Double.parseDouble(output("(write (current-second))"));
        double after = System.currentTimeMillis() / 1000.0;
        assertTrue(before - 1 <= second && second <= after + 1);
    }

    @Test
    public void featuresAreTheCondExpandFeatures() {
        assertEquals("#t #t #t", output("""
                        (display (and (memq 'r7rs (features)) #t))
                        (display " ")
                        (display (pair? (features)))
                        (display " ")
                        (display (cond-expand (truffle-scheme (and (memq 'truffle-scheme (features)) #t)) (else #f)))"""));
    }

    @Test
    public void fileExistsAndDeleteFile() throws IOException {
        Path file = Files.createTempFile("process-time", ".txt");
        String name = file.toString().replace("\\", "\\\\");
        try {
            Result r = eval(SchemeMain.newContext(List.of()), """
                            (define name "%s")
                            (write (list (file-exists? name) (delete-file name) (file-exists? name)
                                         (file-exists? " no such file ")
                                         (file-error? (guard (e (#t e)) (delete-file name)))))""".formatted(name));
            assertEquals(null, r.exitStatus());
            assertTrue(r.out(), r.out().matches("\\(#t .* #f #f #t\\)"));
            assertTrue(!Files.exists(file));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void launcherPassesTheCommandLineAndExitStatus() throws IOException {
        Path file = Files.createTempFile("process-time", ".scm");
        try {
            Files.writeString(file, PRELUDE + "(write (cdr (command-line))) (exit 9)\n");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int status = SchemeMain.run(new String[]{file.toString(), "a", "b"}, out, new ByteArrayOutputStream());
            assertEquals(9, status);
            assertEquals("(\"a\" \"b\")", out.toString(StandardCharsets.UTF_8));
        } finally {
            Files.delete(file);
        }
    }

    @Test
    public void launcherSeesTheProcessEnvironment() throws IOException {
        Path file = Files.createTempFile("process-time", ".scm");
        try {
            Files.writeString(file, PRELUDE + "(write (string? (get-environment-variable \"PATH\")))\n");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int status = SchemeMain.run(new String[]{file.toString()}, out, new ByteArrayOutputStream());
            assertEquals(0, status);
            assertEquals("#t", out.toString(StandardCharsets.UTF_8));
        } finally {
            Files.delete(file);
        }
    }
}
