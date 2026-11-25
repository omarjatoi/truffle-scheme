package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.SandboxPolicy;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
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
        try (Context context = constrained().option("scheme.LibraryPath", "/lib").option("scheme.BufferOutput", "false").option("scheme.InteractiveInput", "true").option("scheme.StackSize",
                        "8m").build()) {
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

    /**
     * The {@code ISOLATED} and {@code UNTRUSTED} policies, which run the language in a polyglot
     * isolate, the library {@code //:scheme_isolate} carries (design.md D10, "ISOLATED and
     * UNTRUSTED"). JUnit runs a nested class only when it is named: {@code
     * //tests:unit_isolate_SandboxTest} runs this one, with that jar on the module path, when it is
     * named, as the native launcher's tests are.
     */
    public static class Isolated {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final ByteArrayOutputStream err = new ByteArrayOutputStream();

        /** A context under ISOLATED, with the limits it requires. */
        private Context.Builder isolated() {
            return Context.newBuilder(SCHEME).sandbox(SandboxPolicy.ISOLATED).out(out).err(err).option("engine.MaxIsolateMemory", "256MB").option("sandbox.MaxCPUTime", "20s");
        }

        /** A context under UNTRUSTED, with the limits it requires, generous enough for these programs. */
        private Context.Builder untrusted() {
            return Context.newBuilder(SCHEME) //
                            .sandbox(SandboxPolicy.UNTRUSTED) //
                            .in(new ByteArrayInputStream("a line\n".getBytes(StandardCharsets.UTF_8))) //
                            .out(out) //
                            .err(err) //
                            .option("engine.MaxIsolateMemory", "512MB") //
                            .option("sandbox.MaxHeapMemory", "128MB") //
                            .option("sandbox.MaxCPUTime", "20s") //
                            .option("sandbox.MaxASTDepth", "50") //
                            .option("sandbox.MaxThreads", "1") //
                            .option("sandbox.MaxOutputStreamSize", "1MB") //
                            .option("sandbox.MaxErrorStreamSize", "1MB");
        }

        /** Programs both policies run: a procedure from Java, the standard libraries, continuations, a deep recursion. */
        private void runsPrograms(Context context) {
            assertEquals("6", eval(context, "(define (f x) (* x 2)) (f 3)"));
            Value exclaim = context.eval(SCHEME, "(lambda (s) (string-append s \"!\"))");
            assertEquals("hello!", exclaim.execute("hello").asString());
            assertEquals("((1 . a) (2 . b))", eval(context, "(map cons '(1 2) '(a b))"));
            assertEquals("\"cba\"", eval(context, "(list->string (reverse (string->list \"abc\")))"));
            assertEquals("42", eval(context, "(call/cc (lambda (k) (dynamic-wind (lambda () #f) (lambda () (k 42)) (lambda () #f))))"));
            // Records are static objects, whose accesses UNTRUSTED checks since Truffle 25.6
            // (engine.ForceStaticObjectSafetyChecks, docs/graalvm-configuration.md).
            assertEquals("7", eval(context, "(define-record-type point (make-point x y) point? (x point-x set-point-x!) (y point-y))" +
                            "(let ((p (make-point 1 2))) (set-point-x! p 5) (+ (point-x p) (point-y p)))"));
            // Deep enough that its frames spill to the heap (design.md "Continuations").
            assertEquals("100000", eval(context, DEPTH + "(depth 100000)"));
            assertEquals("1000000", eval(context, "(let loop ((i 0)) (if (< i 1000000) (loop (+ i 1)) i))"));
            context.eval(SCHEME, "(display \"out\") (write-string \"err\" (current-error-port))");
        }

        @Test
        public void anIsolatedContextRunsScheme() {
            try (Context context = isolated().build()) {
                runsPrograms(context);
            }
            assertEquals("out", out.toString(StandardCharsets.UTF_8));
            assertEquals("err", err.toString(StandardCharsets.UTF_8));
        }

        @Test
        public void anIsolatedContextReachesNoHostFileOrVariable() {
            // ISOLATED keeps CONSTRAINED's restrictions, in the isolate. (A boolean from the
            // isolate is a Java one: its string is "true", not Scheme's "#t".)
            try (Context context = isolated().build()) {
                assertTrue(context.eval(SCHEME, "(guard (e (#t (file-error? e))) (open-input-file \"/etc/hosts\"))").asBoolean());
                assertFalse(context.eval(SCHEME, "(get-environment-variable \"PATH\")").asBoolean());
            }
            PolyglotException e = stopped(isolated(), "(exit 7)");
            assertTrue(e.getMessage(), e.isExit());
            assertEquals(7, e.getExitStatus());
        }

        @Test
        public void theCpuTimeLimitStopsAnIsolatedContext() {
            PolyglotException e = stopped(isolated().option("sandbox.MaxCPUTime", "500ms"), ENDLESS);
            assertTrue(e.getMessage(), e.isResourceExhausted());
            assertTrue(e.getMessage(), e.getMessage().contains("Maximum CPU time limit of 500ms exceeded"));
        }

        @Test
        public void anUntrustedContextRunsSchemeUnderTheLimitsItRequires() {
            try (Context context = untrusted().build()) {
                runsPrograms(context);
                // UNTRUSTED requires standard input redirected: Scheme reads the given stream.
                assertEquals("a line", context.eval(SCHEME, "(read-line)").asString());
            }
            assertEquals("out", out.toString(StandardCharsets.UTF_8));
            assertEquals("err", err.toString(StandardCharsets.UTF_8));
        }

        @Test
        public void theAstDepthLimitStopsCodeNestedDeeperThanIt() {
            // Each nested call is an expression of the procedure's body; the guest's roots have
            // their tags materialized under UNTRUSTED, so the sandbox sees how deep they are
            // (design.md D10, "sandbox.MaxASTDepth under UNTRUSTED").
            String nested = "(+ 1 ".repeat(40) + "0" + ")".repeat(40);
            Context.Builder builder = untrusted().option("sandbox.MaxASTDepth", "10");
            Context context = builder.build();
            try {
                assertEquals("6", eval(context, "(+ 1 (+ 1 (+ 1 (+ 1 (+ 1 (+ 1 0))))))"));
                // The limit is per procedure: each lambda's body is a root of its own, so a chain of
                // 30 shallow procedures runs, however deep its calls go.
                StringBuilder chain = new StringBuilder("(define (p0) 0)");
                for (int i = 1; i <= 30; i++) {
                    chain.append(" (define (p").append(i).append(") (+ 1 (p").append(i - 1).append(")))");
                }
                assertEquals("30", eval(context, chain + " (p30)"));
                // The standard libraries are the language's code: theirs don't count (restored from
                // the prelude image, their roots keep their tags unmaterialized).
                assertEquals("(1 4 9)", eval(context, "(map (lambda (x) (* x x)) '(1 2 3))"));
                assertEquals("#(2 3 4)", eval(context, "(vector-map (lambda (x) (+ x 1)) #(1 2 3))"));
                assertEquals("(b 2)", eval(context, "(assoc 'b '((a 1) (b 2)))"));
                assertEquals("\"cba\"", eval(context, "(list->string (reverse (string->list \"abc\")))"));
                PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval(SCHEME, nested));
                assertTrue(e.getMessage(), e.isResourceExhausted());
                assertTrue(e.getMessage(), e.getMessage().contains("AST depth limit of 10 nodes exceeded"));
            } finally {
                closeCancelled(context);
            }
            // So does a procedure nested that deep, when it is first called, and code that eval
            // compiles.
            PolyglotException procedure = stopped(builder, "(define (f) " + nested + ") (f)");
            assertTrue(procedure.getMessage(), procedure.getMessage().contains("AST depth limit of 10 nodes exceeded"));
            PolyglotException evaluated = stopped(builder, "(eval '" + nested + " (environment '(scheme base)))");
            assertTrue(evaluated.getMessage(), evaluated.getMessage().contains("AST depth limit of 10 nodes exceeded"));
        }

        @Test
        public void theHeapLimitStopsAnUntrustedContext() {
            PolyglotException e = stopped(untrusted().option("sandbox.MaxHeapMemory", "64MB"), "(define (grow l) (grow (cons (make-vector 1000 0) l))) (grow '())");
            assertTrue(e.getMessage(), e.isResourceExhausted());
            assertTrue(e.getMessage(), e.getMessage().contains("Maximum heap memory limit of 67108864 bytes exceeded"));
        }

        @Test
        public void theStreamLimitsStopAnUntrustedContext() {
            PolyglotException output = stopped(untrusted().option("sandbox.MaxOutputStreamSize", "10KB"), "(let loop () (display \"hello\") (loop))");
            assertTrue(output.getMessage(), output.isResourceExhausted());
            PolyglotException error = stopped(untrusted().option("sandbox.MaxErrorStreamSize", "10KB"), "(let loop () (write-string \"hello\" (current-error-port)) (loop))");
            assertTrue(error.getMessage(), error.isResourceExhausted());
        }

        @Test
        public void theStatementAndFrameLimitsStopAnUntrustedContext() {
            PolyglotException statements = stopped(untrusted().option("sandbox.MaxStatements", "10000"), ENDLESS);
            assertTrue(statements.getMessage(), statements.isResourceExhausted());
            // The frame limit counts Scheme's frames in the isolate too, those spilled to its heap
            // among them.
            Context context = untrusted().option("sandbox.MaxStackFrames", Integer.toString(LIMIT)).build();
            try {
                assertEquals(Integer.toString(DEEPEST), eval(context, DEPTH + "(depth " + DEEPEST + ")"));
                PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval(SCHEME, "(depth 1000000)"));
                assertTrue(e.getMessage(), e.isResourceExhausted());
                assertTrue(e.getMessage(), e.getMessage().contains("Maximum frame limit of " + LIMIT + " exceeded"));
            } finally {
                closeCancelled(context);
            }
        }

        /** What Truffle logs at {@code log.engine.level=FINE} when a context takes over a pre-initialized one. */
        private static final String PATCHED = "Successfully patched context of language: scheme";

        /** What a program sees of its run, all of which a pre-initialized context must take from the run. */
        private static final String RUN_PROGRAM = """
                        (import (scheme base) (scheme write) (scheme file) (scheme process-context) (greet hello))
                        (display "a") (display "b") (newline)
                        (write (command-line)) (newline)
                        (write (get-environment-variable "M12_11")) (newline)
                        (write (call-with-input-file "/data.txt" read-line)) (newline)
                        (write (greet (read-line))) (newline)
                        (write 'to-stderr (current-error-port))
                        """;

        /** An output stream that keeps each write it gets: unbuffered output is a write per call. */
        private static final class Writes extends OutputStream {
            final List<String> writes = new ArrayList<>();

            @Override
            public void write(int b) {
                writes.add(String.valueOf((char) b));
            }

            @Override
            public void write(byte[] b, int off, int len) {
                writes.add(new String(b, off, len, StandardCharsets.UTF_8));
            }
        }

        /** A zip file of a library, {@code /lib/greet/hello.sld}, and a file, {@code /data.txt}. */
        private static Path runFiles(Path dir) throws IOException {
            Path zip = dir.resolve("run.zip");
            try (ZipOutputStream zipOut = new ZipOutputStream(Files.newOutputStream(zip))) {
                zipOut.putNextEntry(new ZipEntry("lib/greet/hello.sld"));
                zipOut.write("(define-library (greet hello) (export greet) (import (scheme base)) (begin (define (greet name) (string-append \"hello, \" name))))".getBytes(StandardCharsets.UTF_8));
                zipOut.closeEntry();
                zipOut.putNextEntry(new ZipEntry("data.txt"));
                zipOut.write("line one\n".getBytes(StandardCharsets.UTF_8));
                zipOut.closeEntry();
            }
            return zip;
        }

        /**
         * The isolate library is built with a pre-initialized context (design.md D10, "A
         * pre-initialized context in the isolate"), which each isolate's context takes over: it
         * sees its own run's file system, library path, environment, command line and streams, and
         * its own scheme.BufferOutput, nothing of the build's, in one isolate after another.
         */
        @Test
        public void eachIsolatedContextTakesOverAPreInitializedOneWithItsRun() throws IOException {
            Path zip = runFiles(Files.createTempDirectory("isolated"));
            for (String buffer : new String[]{"true", "false"}) {
                Writes writes = new Writes();
                ByteArrayOutputStream errors = new ByteArrayOutputStream();
                ByteArrayOutputStream log = new ByteArrayOutputStream();
                try (java.nio.file.FileSystem zipFs = java.nio.file.FileSystems.newFileSystem(zip)) {
                    IOAccess io = IOAccess.newBuilder().fileSystem(FileSystem.newFileSystem(zipFs)).build();
                    try (Context context = isolated().allowIO(io).option("scheme.LibraryPath", "/lib").option("scheme.BufferOutput", buffer) //
                                    .environment("M12_11", "buffer " + buffer).arguments(SCHEME, new String[]{"prog.scm", buffer}) //
                                    .in(new ByteArrayInputStream("world\n".getBytes(StandardCharsets.UTF_8))).out(writes).err(errors) //
                                    .option("log.engine.level", "FINE").logHandler(log).build()) {
                        context.eval(SCHEME, RUN_PROGRAM);
                    }
                }
                assertTrue(buffer + ": the context is the pre-initialized one", log.toString(StandardCharsets.UTF_8).contains(PATCHED));
                String expected = "ab\n(\"prog.scm\" \"" + buffer + "\")\n\"buffer " + buffer + "\"\n\"line one\"\n\"hello, world\"\n";
                assertEquals(expected, String.join("", writes.writes));
                // Buffered, output is written when Scheme reads standard input and when control
                // returns; unbuffered, at each write.
                assertEquals(buffer, buffer.equals("true") ? 2 : 11, writes.writes.size());
                assertEquals("to-stderr", errors.toString(StandardCharsets.UTF_8));
            }
        }

        /** UNTRUSTED's contexts take over the pre-initialized context too, with their own policy. */
        @Test
        public void anUntrustedContextTakesOverAPreInitializedOne() {
            ByteArrayOutputStream log = new ByteArrayOutputStream();
            Context context = untrusted().option("log.engine.level", "FINE").logHandler(log).build();
            try {
                assertEquals("a line", context.eval(SCHEME, "(read-line)").asString());
                // The guest's roots have their tags materialized, as the run's policy asks.
                String nested = "(+ 1 ".repeat(60) + "0" + ")".repeat(60);
                PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval(SCHEME, nested));
                assertTrue(e.getMessage(), e.getMessage().contains("AST depth limit of 50 nodes exceeded"));
            } finally {
                closeCancelled(context);
            }
            assertTrue(log.toString(StandardCharsets.UTF_8), log.toString(StandardCharsets.UTF_8).contains(PATCHED));
        }

        /**
         * A context that doesn't restore the prelude image declines the pre-initialized one, which
         * restored it, and gets a new context, which expands the standard libraries.
         */
        @Test
        public void aContextWithoutThePreludeImageDeclinesThePreInitializedOne() {
            ByteArrayOutputStream log = new ByteArrayOutputStream();
            try (Context context = isolated().option("scheme.PreludeImage", "false").option("log.engine.level", "FINE").logHandler(log).build()) {
                assertEquals("((1 . a) (2 . b))", eval(context, "(map cons '(1 2) '(a b))"));
            }
            assertFalse(log.toString(StandardCharsets.UTF_8), log.toString(StandardCharsets.UTF_8).contains(PATCHED));
        }

        @Test
        public void theOptionsAnEmbedderSetsWorkUnderUntrustedButThePreludeImages() {
            try (Context context = untrusted().option("scheme.LibraryPath", "/lib").option("scheme.BufferOutput", "false").option("scheme.InteractiveInput", "true").option("scheme.StackSize",
                            "8m").build()) {
                assertEquals("3", eval(context, "(+ 1 2)"));
            }
            // Expanded rather than restored, the standard libraries' roots would count towards
            // sandbox.MaxASTDepth.
            Context.Builder builder = untrusted().option("scheme.PreludeImage", "false");
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);
            assertTrue(e.getMessage(), e.getMessage().contains("scheme.PreludeImage"));
        }
    }
}
