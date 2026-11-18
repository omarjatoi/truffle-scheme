package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.SandboxPolicy;
import org.graalvm.polyglot.io.FileSystem;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The language under GraalVM's sandbox policies and resource limits (docs/graalvm-configuration.md,
 * "Sandboxing"): it declares {@code SandboxPolicy.CONSTRAINED}, since it reaches no host resource
 * but through the Env (design.md D10), and Truffle's limits stop a context that runs too long or
 * writes too much.
 */
public class SandboxTest {

    private static final String SCHEME = "scheme";
    private static final String ENDLESS = "(let loop ((i 0)) (loop (+ i 1)))";

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private Engine engine;

    /**
     * The contexts' engine, which doesn't read {@code polyglot.*} system properties: the compile
     * mode's JVM gives engine options that CONSTRAINED refuses ({@code engine.BackgroundCompilation}).
     */
    @Before
    public void createEngine() {
        engine = Engine.newBuilder(SCHEME).sandbox(SandboxPolicy.CONSTRAINED).useSystemProperties(false).out(OutputStream.nullOutputStream()).err(OutputStream.nullOutputStream()).build();
    }

    @After
    public void closeEngine() {
        engine.close();
    }

    /** A context under CONSTRAINED, which needs the language named and both streams redirected. */
    private Context.Builder constrained() {
        return Context.newBuilder(SCHEME).engine(engine).sandbox(SandboxPolicy.CONSTRAINED).out(out).err(err);
    }

    private static String eval(Context context, String text) {
        return context.eval(SCHEME, text).toString();
    }

    /**
     * Evaluates {@code text} in a context of {@code builder}'s, which must stop it: the exception.
     * A context that was cancelled or exited throws it again as it closes.
     */
    private static PolyglotException stopped(Context.Builder builder, String text) {
        try (Context context = builder.build()) {
            context.eval(SCHEME, text);
        } catch (PolyglotException e) {
            return e;
        }
        fail("not stopped: " + text);
        return null;
    }

    @Test
    public void aConstrainedContextRunsSchemeAndWritesToItsStreams() {
        try (Context context = constrained().build()) {
            assertEquals("6", eval(context, "(define (f x) (* x 2)) (f 3)"));
            context.eval(SCHEME, "(display \"out\") (write-string \"err\" (current-error-port))");
            assertEquals("((1 . a) (2 . b))", eval(context, "(map cons '(1 2) '(a b))"));
        }
        assertEquals("out", out.toString(StandardCharsets.UTF_8));
        assertEquals("err", err.toString(StandardCharsets.UTF_8));
    }

    @Test
    public void aConstrainedContextTakesTheOptionsAnEmbedderSets() {
        try (Context context = constrained().option("scheme.LibraryPath", "/lib").option("scheme.BufferOutput", "false").option("scheme.StackSize", "8m").build()) {
            assertEquals("3", eval(context, "(+ 1 2)"));
        }
        // An internal option for tests isn't one of them.
        Context.Builder builder = constrained().allowExperimentalOptions(true).option("scheme.SpillDepth", "10");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(e.getMessage(), e.getMessage().contains("scheme.SpillDepth"));
    }

    @Test
    public void aConstrainedContextReachesNoHostFileOrVariable() {
        // CONSTRAINED presets IOAccess.NONE and EnvironmentAccess.NONE: Scheme sees file errors
        // and no environment variables.
        try (Context context = constrained().build()) {
            assertEquals("#t", eval(context, "(guard (e (#t (file-error? e))) (open-input-file \"" + folder.getRoot() + "/x\"))"));
            assertEquals("#t", eval(context, "(guard (e (#t (file-error? e))) (file-exists? \"" + folder.getRoot() + "\"))"));
            assertEquals("#f", eval(context, "(get-environment-variable \"PATH\")"));
            assertEquals("()", eval(context, "(get-environment-variables)"));
        }
    }

    @Test
    public void theHostFileSystemIsRefused() {
        // The sandbox's own check: CONSTRAINED refuses the host's file system.
        Context.Builder builder = constrained().allowIO(IOAccess.ALL);
        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    public void aConstrainedContextReadsLibrariesThroughTheEmbeddersFileSystem() throws IOException {
        // CONSTRAINED allows a file system of the embedder's: here a zip file's, as in
        // FileSystem.newFileSystem's Javadoc.
        Path zip = folder.getRoot().toPath().resolve("libraries.zip");
        try (ZipOutputStream zipOut = new ZipOutputStream(Files.newOutputStream(zip))) {
            zipOut.putNextEntry(new ZipEntry("lib/sandboxed/greet.sld"));
            zipOut.write("(define-library (sandboxed greet) (import (scheme base)) (export greet) (begin (define (greet name) (string-append \"Hello, \" name))))".getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }
        try (java.nio.file.FileSystem zipFs = java.nio.file.FileSystems.newFileSystem(zip)) {
            IOAccess io = IOAccess.newBuilder().fileSystem(FileSystem.newFileSystem(zipFs)).build();
            try (Context context = constrained().allowIO(io).option("scheme.LibraryPath", "/lib").build()) {
                assertEquals("Hello, sandbox", context.eval(SCHEME, "(import (scheme base) (sandboxed greet)) (greet \"sandbox\")").asString());
            }
        }
    }

    @Test
    public void exitEndsTheContextNotTheVM() {
        // CONSTRAINED refuses the host's System.exit: exit is Truffle's (design.md D9).
        for (String exit : new String[]{"(exit 7)", "(emergency-exit 7)"}) {
            PolyglotException e = stopped(constrained(), exit);
            assertTrue(e.getMessage(), e.isExit());
            assertEquals(7, e.getExitStatus());
        }
    }

    @Test
    public void aStatementLimitStopsAnEndlessLoop() {
        // Each expression of a body is a statement (design.md D13), which the limit counts.
        ResourceLimits limits = ResourceLimits.newBuilder().statementLimit(10_000, null).build();
        PolyglotException e = stopped(constrained().resourceLimits(limits), ENDLESS);
        assertTrue(e.getMessage(), e.isResourceExhausted());
        assertTrue(e.isCancelled());
    }

    @Test
    public void sandboxLimitsStopAContextThatRunsOrWritesTooMuch() {
        PolyglotException time = stopped(constrained().option("sandbox.MaxCPUTime", "100ms"), ENDLESS);
        assertTrue(time.getMessage(), time.isResourceExhausted());
        // Standard output reaches the limit's stream as its buffer is written (design.md D10).
        PolyglotException output = stopped(constrained().option("sandbox.MaxOutputStreamSize", "10KB"), "(let loop () (display \"hello\") (loop))");
        assertTrue(output.getMessage(), output.isResourceExhausted());
    }

    @Test
    public void closingACancelsARunningEvaluation() throws InterruptedException {
        Context context = constrained().build();
        Thread closer = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                return;
            }
            context.close(true);
        });
        closer.start();
        PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval(SCHEME, ENDLESS));
        assertTrue(e.getMessage(), e.isCancelled());
        closer.join();
    }
}
