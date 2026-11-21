package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * M8-07: a context pre-initialized in the native image (design.md D17) is patched, not rebuilt,
 * when the launcher or an embedder creates its context: {@code SchemeLanguage.patchContext} rebinds
 * everything the context took from the build-time Env (the console ports, the command line, the
 * environment variables, the current directory, the library path), so nothing of the build leaks
 * into a run.
 *
 * <p>On HotSpot, Truffle pre-initializes a context in the process when
 * {@code polyglot.image-build-time.PreinitializeContexts} is set before the polyglot API is first
 * used, as native-image does at build time, and the first context with the default engine is the
 * patched one. Each check runs in a child JVM that sets it, so it is that JVM's first context, and
 * asserts from the engine's log that the context was patched. The children don't take this JVM's
 * engine options: with {@code BackgroundCompilation=false} (the compile mode), Truffle's HotSpot
 * pre-initialization deadlocks, as it waits for a compilation inside the polyglot API's class
 * initializer, which the compiler thread waits for in turn. Native-image doesn't compile at build
 * time.
 */
public class ContextPreinitializationTest {

    private static final String SCHEME = "scheme";
    private static final String PREINITIALIZE = "polyglot.image-build-time.PreinitializeContexts";
    private static final String PATCHED = "Successfully patched context of language: scheme";

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private Path file(String name, String text) throws IOException {
        Path path = folder.getRoot().toPath().resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
        return path;
    }

    private static final String GREET_LIBRARY = """
                    (define-library (greet hello)
                      (export greet)
                      (import (scheme base))
                      (begin (define (greet name) (string-append "hello, " name))))
                    """;

    /** What a program sees of its process: everything that a build-time context must not keep. */
    private static final String PROCESS_PROGRAM = """
                    (import (scheme base) (scheme write) (scheme file) (scheme process-context) (scheme time) (greet hello))
                    (write (command-line)) (newline)
                    (write (get-environment-variable "M8_07")) (newline)
                    (write (assoc "M8_07" (get-environment-variables))) (newline)
                    (write (call-with-input-file "data.txt" read-line)) (newline)
                    (with-output-to-file "written.txt" (lambda () (display "from the run")))
                    (write (file-exists? "written.txt")) (newline)
                    (write (greet (read-line))) (newline)
                    (write (> (current-second) 1.7e9)) (newline)
                    (write 'to-stderr (current-error-port))
                    (flush-output-port (current-error-port))
                    (exit 5)
                    """;

    private record Result(int status, String out, String err) {
    }

    /**
     * A child JVM on this one's module path that pre-initializes a context, running
     * {@code mainClass} (module/class) in {@code dir}, with {@code env} added to its environment.
     */
    private Result launch(Path dir, Map<String, String> env, String stdin, String mainClass, String... args) throws IOException, InterruptedException {
        String java = ProcessHandle.current().info().command().orElseThrow();
        String modulePath = System.getProperty("jdk.module.path");
        assertNotNull("the tests run on the module path", modulePath);
        List<String> command = new ArrayList<>(List.of(java, "--module-path", modulePath, "--add-modules", "ALL-MODULE-PATH", "--enable-native-access=org.graalvm.truffle,org.graalvm.shadowed.jline",
                        "--sun-misc-unsafe-memory-access=allow", "-D" + PREINITIALIZE + "=" + SCHEME, "-m", mainClass));
        command.addAll(List.of(args));
        Path in = folder.newFile().toPath();
        Files.writeString(in, stdin, StandardCharsets.UTF_8);
        Path out = folder.newFile().toPath();
        Path err = folder.newFile().toPath();
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile()).redirectInput(in.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile());
        builder.environment().putAll(env);
        Process process = builder.start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError(mainClass + " " + String.join(" ", args) + " timed out");
        }
        return new Result(process.exitValue(), Files.readString(out, StandardCharsets.UTF_8), Files.readString(err, StandardCharsets.UTF_8));
    }

    /** The launcher in a child JVM that pre-initializes a context. */
    private Result scheme(Path dir, Map<String, String> env, String stdin, String... args) throws IOException, InterruptedException {
        List<String> all = new ArrayList<>(List.of("--log.engine.level=FINE"));
        all.addAll(List.of(args));
        return launch(dir, env, stdin, "scheme.launcher/scheme.launcher.SchemeMain", all.toArray(new String[0]));
    }

    /**
     * Through the Context API ({@link #main}, in a child JVM): the patched context's streams,
     * arguments, environment, current directory and library path are all the run's, none the
     * pre-initialization's.
     */
    @Test
    public void thePatchedContextSeesTheRunsEnvironment() throws IOException, InterruptedException {
        Path dir = folder.newFolder("run").toPath();
        Files.writeString(dir.resolve("data.txt"), "line one\nline two\n", StandardCharsets.UTF_8);
        file("libs/greet/hello.sld", GREET_LIBRARY);
        Result r = launch(folder.getRoot().toPath(), Map.of(), "", "scheme.test/" + ContextPreinitializationTest.class.getName(), dir.toString(),
                        folder.getRoot().toPath().resolve("libs").toString());
        assertEquals(r.out() + r.err(), 0, r.status());
        assertEquals("from the run", Files.readString(dir.resolve("written.txt"), StandardCharsets.UTF_8));
    }

    /**
     * The first context of this process, from the Context API, in {@code args[0]} with the library
     * path {@code args[1]}: fails with an AssertionError (and a nonzero status) if it isn't the
     * patched context or sees anything of the pre-initialization.
     */
    public static void main(String[] args) {
        assertEquals(SCHEME, System.getProperty(PREINITIALIZE));
        Path dir = Path.of(args[0]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        Integer status = null;
        try (Context context = Context.newBuilder(SCHEME).allowIO(IOAccess.ALL).allowEnvironmentAccess(EnvironmentAccess.NONE).environment(Map.of("M8_07", "at run time")) //
                        .currentWorkingDirectory(dir).arguments(SCHEME, new String[]{"prog.scm", "a", "b"}).option("scheme.LibraryPath", args[1]) //
                        .in(new ByteArrayInputStream("world\n".getBytes(StandardCharsets.UTF_8))).out(out).err(err) //
                        .option("log.engine.level", "FINE").logHandler(log).build()) {
            context.eval(SCHEME, PROCESS_PROGRAM);
        } catch (PolyglotException e) {
            assertTrue(e.toString(), e.isExit());
            status = e.getExitStatus();
        }
        String logged = log.toString(StandardCharsets.UTF_8);
        assertTrue("the context is the pre-initialized one: " + logged, logged.contains(PATCHED));
        assertEquals(err.toString(StandardCharsets.UTF_8), Integer.valueOf(5), status);
        assertEquals("""
                        ("prog.scm" "a" "b")
                        "at run time"
                        ("M8_07" . "at run time")
                        "line one"
                        #t
                        "hello, world"
                        #t
                        """, out.toString(StandardCharsets.UTF_8));
        assertEquals("to-stderr", err.toString(StandardCharsets.UTF_8));
    }

    @Test
    public void theLauncherRunsAProgramInThePatchedContext() throws IOException, InterruptedException {
        Path dir = folder.newFolder("prog").toPath();
        Files.writeString(dir.resolve("data.txt"), "line one\n", StandardCharsets.UTF_8);
        Path program = Files.writeString(dir.resolve("prog.scm"), PROCESS_PROGRAM, StandardCharsets.UTF_8);
        file("libs/greet/hello.sld", GREET_LIBRARY);
        Result r = scheme(dir, Map.of("M8_07", "in the child"), "child\n", "-I", folder.getRoot().toPath().resolve("libs").toString(), program.toString(), "x");
        assertTrue("the context is the pre-initialized one: " + r.err(), r.err().contains(PATCHED));
        assertEquals(r.err(), 5, r.status());
        assertEquals("(\"" + program + "\" \"x\")\n\"in the child\"\n(\"M8_07\" . \"in the child\")\n\"line one\"\n#t\n\"hello, child\"\n#t\n", r.out());
        assertTrue(r.err(), r.err().contains("to-stderr"));
        assertEquals("from the run", Files.readString(dir.resolve("written.txt"), StandardCharsets.UTF_8));
    }

    /**
     * The console input port, which the prelude's parameters hold from the pre-initialization, takes
     * the run's {@code scheme.InteractiveInput} (design.md D10, "The console's end"): at the end of
     * standard input (a file here), {@code char-ready?} is #t, unless the run says standard input is
     * a terminal, where it is #f.
     */
    @Test
    public void thePatchedConsoleInputTakesTheRunsInteractiveInput() throws IOException, InterruptedException {
        Path dir = folder.newFolder("console-end").toPath();
        Path program = Files.writeString(dir.resolve("prog.scm"), "(import (scheme base) (scheme write) (scheme char))\n(write (list (read-char) (eof-object? (read-char)) (char-ready?)))\n",
                        StandardCharsets.UTF_8);
        for (boolean interactive : new boolean[]{false, true}) {
            Result r = scheme(dir, Map.of(), "a", "--scheme.InteractiveInput=" + interactive, program.toString());
            assertTrue("the context is the pre-initialized one: " + r.err(), r.err().contains(PATCHED));
            assertEquals(r.err(), 0, r.status());
            assertEquals("scheme.InteractiveInput=" + interactive, "(#\\a #t " + (interactive ? "#f" : "#t") + ")", r.out());
        }
    }

    @Test
    public void theReplRunsInThePatchedContext() throws IOException, InterruptedException {
        Path dir = folder.newFolder("repl").toPath();
        Result r = scheme(dir, Map.of(), "(define x 20)\n(+ x 1 (string-length (read-line)))\nabc\n(command-line)\n");
        assertTrue("the context is the pre-initialized one: " + r.err(), r.err().contains(PATCHED));
        assertEquals(r.err(), 0, r.status());
        assertEquals("24\n()\n", r.out());
        assertFalse(r.err(), r.err().contains("ERROR"));
    }
}
