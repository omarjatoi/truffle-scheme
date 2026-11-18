package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The language's loggers (design.md D13, "Logging"; docs/usage.md): {@code scheme.spill},
 * {@code scheme.continuations}, {@code scheme.prelude} and {@code scheme.library} log their rare
 * events at {@code FINE}, through the context builder's options and the launcher's, to the log
 * handler or the file Truffle is given; with no level set, nothing.
 */
public class LoggingTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    /** A deep recursion that spills, a continuation captured and re-entered, a library imported. */
    private static final String PROGRAM = """
                    (import (scheme base) (examples twice))
                    (define (down n) (if (= n 0) 0 (+ 1 (down (- n 1)))))
                    (define k #f)
                    (define n (+ 1 (call/cc (lambda (c) (set! k c) 0))))
                    (if (< n 3) (k n))
                    (twice (down 100))
                    """;

    private Path libraries() throws IOException {
        Path dir = folder.newFolder("lib", "examples").toPath();
        Files.writeString(dir.resolve("twice.sld"), "(define-library (examples twice) (import (scheme base)) (export twice) (begin (define (twice x) (* 2 x))))",
                        StandardCharsets.UTF_8);
        return dir.getParent();
    }

    private Context.Builder builder(Path libraries) {
        return Context.newBuilder("scheme").out(new ByteArrayOutputStream()).allowIO(IOAccess.ALL).option("scheme.LibraryPath", libraries.toString()).allowExperimentalOptions(true)
                        // Spills every 10 calls, so a recursion 100 deep spills.
                        .option("scheme.SpillDepth", "10");
    }

    private static void assertLogs(String log, String... lines) {
        for (String line : lines) {
            assertTrue(line + " in:\n" + log, log.contains(line));
        }
    }

    @Test
    public void theContextBuildersOptionsTurnThemOn() throws IOException {
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        try (Context context = builder(libraries()).option("log.scheme.level", "FINE").logHandler(log).build()) {
            assertEquals(200, context.eval("scheme", PROGRAM).asInt());
        }
        String text = log.toString(StandardCharsets.UTF_8);
        assertLogs(text, "[scheme::prelude] FINE: restored the standard libraries from the prelude image in ",
                        "[scheme::spill] FINE: spill 1: ",
                        "frames moved to the heap (the limit: 10 calls)",
                        "[scheme::continuations] FINE: captured a continuation of 1 frame",
                        "[scheme::continuations] FINE: re-entered a continuation of 1 frame, in place of the current one",
                        "[scheme::library] FINE: library (examples twice) loaded from ");
        // Not a log line for every call, nor for every spilled frame's return.
        long lines = text.lines().count();
        assertTrue(lines + " lines:\n" + text, lines < 40);
    }

    @Test
    public void oneComponentAtATime() throws IOException {
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        try (Context context = builder(libraries()).option("log.scheme.spill.level", "FINE").logHandler(log).build()) {
            assertEquals(200, context.eval("scheme", PROGRAM).asInt());
        }
        String text = log.toString(StandardCharsets.UTF_8);
        assertLogs(text, "[scheme::spill] FINE: spill 1: ");
        assertFalse(text, text.contains("[scheme::continuations]") || text.contains("[scheme::library]") || text.contains("[scheme::prelude]"));
    }

    @Test
    public void nothingUnlessALevelIsSet() throws IOException {
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        try (Context context = builder(libraries()).logHandler(log).build()) {
            assertEquals(200, context.eval("scheme", PROGRAM).asInt());
        }
        assertEquals("", log.toString(StandardCharsets.UTF_8));
    }

    @Test
    public void aLibraryNotFoundSaysWhereItLooked() throws IOException {
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        Path libraries = libraries();
        try (Context context = builder(libraries).option("log.scheme.library.level", "FINE").logHandler(log).build()) {
            context.eval("scheme", "(import (scheme base) (examples missing))");
        } catch (org.graalvm.polyglot.PolyglotException e) {
            assertTrue(e.getMessage(), e.isSyntaxError());
        }
        assertLogs(log.toString(StandardCharsets.UTF_8), "[scheme::library] FINE: library (examples missing) not found: no examples/missing.sld in the library path [" + libraries + "]");
    }

    @Test
    public void theLogFileOption() throws IOException {
        Path file = folder.getRoot().toPath().resolve("context.log");
        try (Context context = builder(libraries()).option("log.scheme.level", "FINE").option("log.file", file.toString()).build()) {
            assertEquals(200, context.eval("scheme", PROGRAM).asInt());
        }
        assertLogs(Files.readString(file, StandardCharsets.UTF_8), "[scheme::spill] FINE: spill 1: ", "[scheme::library] FINE: library (examples twice) loaded from ");
    }

    /** The launcher takes the same options, {@code --log.scheme.level=FINE} and {@code --log.file}. */
    @Test
    public void theLaunchersOptions() throws IOException, InterruptedException {
        Path program = folder.getRoot().toPath().resolve("prog.scm");
        Files.writeString(program, PROGRAM.replace("(scheme base)", "(scheme base) (scheme write)").replace("(twice (down 100))", "(display (twice (down 100000)))"), StandardCharsets.UTF_8);
        Path file = folder.getRoot().toPath().resolve("launcher.log");
        String java = ProcessHandle.current().info().command().orElseThrow();
        String modulePath = System.getProperty("jdk.module.path");
        assertNotNull("the tests run on the module path", modulePath);
        List<String> command = new ArrayList<>(List.of(java, "--module-path", modulePath, "--enable-native-access=org.graalvm.truffle", "--sun-misc-unsafe-memory-access=allow",
                        "-m", "scheme.launcher/scheme.launcher.SchemeMain", "--log.scheme.level=FINE", "--log.file=" + file, "-I", libraries().toString(), program.toString()));
        Path out = folder.newFile().toPath();
        Path err = folder.newFile().toPath();
        Process process = new ProcessBuilder(command).directory(folder.getRoot()).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("the launcher timed out");
        }
        String errors = Files.readString(err, StandardCharsets.UTF_8);
        assertEquals(errors, 0, process.exitValue());
        assertEquals("200000", Files.readString(out, StandardCharsets.UTF_8));
        // All of it in the file, none on standard error.
        assertEquals("", errors);
        assertLogs(Files.readString(file, StandardCharsets.UTF_8), "[scheme::prelude] FINE: ", "[scheme::continuations] FINE: captured a continuation of 1 frame",
                        "[scheme::library] FINE: library (examples twice) loaded from ", "[scheme::spill] FINE: the stack of thread ");
    }
}
