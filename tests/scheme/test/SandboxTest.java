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
import org.graalvm.polyglot.Source;
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

    // sandbox.MaxStackFrames counts Scheme frames, those a deep recursion keeps on the heap too
    // (design.md D10, "Sandboxing"; docs/embedding.md).

    private static final String DEPTH = "(define (depth n) (if (= n 0) 0 (+ 1 (depth (- n 1)))))";
    /** Spill depths for the frame limit's tests: where the stack needs it (null), and every few calls. */
    private static final String[] SPILL_DEPTHS = {null, "1", "5", "20"};
    /** The frame limit of these tests: a program's body is a frame, and (depth n) adds n + 1. */
    private static final int LIMIT = 100;
    private static final int DEEPEST = LIMIT - 2;

    /**
     * A context with {@link #LIMIT} as its frame limit, which spills its frames every
     * {@code spillDepth} nested calls (null: as its stack needs). A trusted one: CONSTRAINED refuses
     * {@code scheme.SpillDepth}, an option for tests.
     */
    private Context.Builder framed(String spillDepth) {
        Context.Builder builder = Context.newBuilder(SCHEME).out(out).err(err).option("sandbox.MaxStackFrames", Integer.toString(LIMIT));
        return spillDepth == null ? builder : builder.allowExperimentalOptions(true).option("scheme.SpillDepth", spillDepth);
    }

    /**
     * That {@code context}'s frame count is where it started: a recursion as deep as the limit
     * allows still runs, and one a frame deeper is stopped. Cancels the context
     * ({@link #closeCancelled}).
     */
    private static void countsFramesFromZero(String what, Context context) {
        assertEquals(what, Integer.toString(DEEPEST), eval(context, "(depth " + DEEPEST + ")"));
        PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval(SCHEME, "(depth " + (DEEPEST + 1) + ")"));
        assertTrue(what + ": " + e.getMessage(), e.isResourceExhausted());
        assertTrue(what + ": " + e.getMessage(), e.getMessage().contains("Maximum frame limit of " + LIMIT + " exceeded. Frames on stack: " + (LIMIT + 1)));
    }

    /** Closes a context that may have been cancelled, which throws its cancellation again. */
    private static void closeCancelled(Context context) {
        try {
            context.close();
        } catch (PolyglotException e) {
            if (!e.isCancelled()) {
                throw e;
            }
        }
    }

    @Test
    public void theFrameLimitStopsARecursionDeeperThanItEvenWhenItsFramesSpill() {
        // A recursion as deep as the limit allows runs, and one a frame deeper is stopped, however
        // many of its frames are on the heap: one every call, every 5 or 20, or as the stack needs.
        for (String spill : SPILL_DEPTHS) {
            Context context = framed(spill).build();
            try {
                context.eval(SCHEME, DEPTH);
                countsFramesFromZero("spill depth " + spill, context);
            } finally {
                closeCancelled(context);
            }
        }
        // Under CONSTRAINED, where the stack decides: the first spill is 50 calls deep.
        Context context = constrained().option("sandbox.MaxStackFrames", Integer.toString(LIMIT)).build();
        try {
            assertEquals(Integer.toString(DEEPEST), eval(context, DEPTH + "(depth " + DEEPEST + ")"));
            PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval(SCHEME, "(depth 1000000)"));
            assertTrue(e.getMessage(), e.isResourceExhausted());
            assertTrue(e.getMessage(), e.getMessage().contains("Maximum frame limit of " + LIMIT + " exceeded"));
        } finally {
            closeCancelled(context);
        }
    }

    @Test
    public void tailCallsDontGrowTheFrameCount() {
        for (String spill : SPILL_DEPTHS) {
            try (Context context = framed(spill).option("sandbox.MaxStackFrames", "10").build()) {
                assertEquals("spill depth " + spill, "1000000", eval(context, "(let loop ((i 0)) (if (< i 1000000) (loop (+ i 1)) i))"));
                // A non-tail call in each iteration returns before the next.
                assertEquals("spill depth " + spill, "100000", eval(context, "(define (add1 x) (+ x 1)) (let loop ((i 0)) (if (< i 100000) (loop (add1 i)) i))"));
            }
        }
    }

    @Test
    public void reenteringAndEscapingDontDriftTheFrameCount() {
        String program = DEPTH + """
                        (define k #f)
                        (define (deep m) (if (= m 0) (call/cc (lambda (c) (set! k c) 0)) (+ 1 (deep (- m 1)))))
                        (define (escape m out) (if (= m 0) (out 'out) (+ 1 (escape (- m 1) out))))
                        (define (deep-raise m) (if (= m 0) (raise 'boom) (+ 1 (deep-raise (- m 1)))))
                        (define (deep-error m) (if (= m 0) (car '()) (+ 1 (deep-error (- m 1)))))
                        (define (repeat times thunk) (let loop ((i 0)) (if (< i times) (begin (thunk) (loop (+ i 1))))))
                        ;; A continuation 60 frames deep, re-entered 200 times from the top: the count is
                        ;; exact, so a frame lost or gained each time would show.
                        (define n 0)
                        (deep 60)
                        (set! n (+ n 1))
                        (if (< n 200) (k 0))
                        ;; Escapes from 60 frames deep (a full continuation, and an escape-only one,
                        ;; whose escape procedure is a local's), and raises caught 60 frames up.
                        (repeat 200 (lambda () (call/cc (lambda (out) (escape 60 out)))))
                        (define (escape-only)
                          (define (escape m out) (if (= m 0) (out 'out) (+ 1 (escape (- m 1) out))))
                          (call/cc (lambda (out) (escape 60 out))))
                        (repeat 200 escape-only)
                        (repeat 200 (lambda () (guard (e (#t 0)) (deep-raise 60))))
                        ;; A composable continuation 30 frames deep, reinstated 200 times 30 frames
                        ;; deep: into the root prompt, and into an inner one, which a spill passes
                        ;; and an abort leaves.
                        (define (deep-capture m) (if (= m 0) (%capture-to-prompt 'p (lambda (c) c)) (+ 1 (deep-capture (- m 1)))))
                        (define composable (%call-with-prompt 'p (lambda () (deep-capture 30))))
                        (define (reinstate d) (if (= d 0) (composable 0) (+ 1 (reinstate (- d 1)))))
                        (repeat 200 (lambda () (reinstate 30)))
                        (define (deep-abort m)
                          (if (= m 0)
                              (begin (%capture-to-prompt 'p (lambda (c) c)) (depth 20) (%capture-to-prompt 'q (lambda (c) 0)))
                              (+ 1 (deep-abort (- m 1)))))
                        (define aborting (%call-with-prompt 'p (lambda () (deep-abort 30))))
                        (define (reinstate-aborting d) (if (= d 0) (aborting 0) (+ 1 (reinstate-aborting (- d 1)))))
                        (repeat 200 (lambda () (%call-with-prompt 'q (lambda () (reinstate-aborting 30)))))
                        ;; ... and one that a full continuation leaves.
                        (define leave-q #f)
                        (define (deep-leave m) (if (= m 0) (begin (%capture-to-prompt 'p (lambda (c) c)) (leave-q 0)) (+ 1 (deep-leave (- m 1)))))
                        (define leaving (%call-with-prompt 'p (lambda () (deep-leave 30))))
                        (define (reinstate-leaving d) (if (= d 0) (leaving 0) (+ 1 (reinstate-leaving (- d 1)))))
                        (repeat 200 (lambda () (call/cc (lambda (out) (set! leave-q out) (%call-with-prompt 'q (lambda () (reinstate-leaving 30)))))))
                        ;; A continuation 60 frames deep, re-entered 200 times from 30 frames deep.
                        (define (again d) (if (= d 0) (k 0) (+ 1 (again (- d 1)))))
                        (set! n 0)
                        (deep 60)
                        (set! n (+ n 1))
                        (if (< n 200) (again 30))
                        """;
        for (String spill : SPILL_DEPTHS) {
            Context context = framed(spill).build();
            try {
                context.eval(SCHEME, program);
                // An error that leaves the program unwinds through its frames. One source,
                // parsed (and compiled) once.
                Source error = Source.create(SCHEME, "(deep-error 60)");
                for (int i = 0; i < 100; i++) {
                    PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval(error));
                    assertTrue(e.getMessage(), e.isGuestException());
                }
                countsFramesFromZero("spill depth " + spill, context);
            } finally {
                closeCancelled(context);
            }
        }
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
