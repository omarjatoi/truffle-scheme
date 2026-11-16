package scheme.test.upstream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.graalvm.polyglot.Context;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Truffle 25.5 bug: on HotSpot, context pre-initialization
 * ({@code -Dpolyglot.image-build-time.PreinitializeContexts}) with
 * {@code -Dpolyglot.engine.BackgroundCompilation=false} deadlocks in the first use of the polyglot
 * API (docs/upstream.md, "Context pre-initialization with synchronous compilation"). The main
 * thread, in {@code Engine$ImplHolder}'s class initializer, waits for the runtime's first
 * compilation; the compiler thread that would run it waits for that class initializer to finish.
 * Our workaround: ContextPreinitializationTest runs its checks in child JVMs that don't take the
 * test JVM's engine options.
 *
 * <p>Each case runs {@link #main} in a child JVM on this one's module path. Inverted, like every
 * test in this package: it passes while the bug is there. When a Truffle upgrade fixes the bug, it
 * fails; then remove the workaround, this test and its entry in docs/upstream.md.
 */
public class UpstreamPreinitializationDeadlockTest {

    /** How long the deadlocked child gets: the control case finishes in about a second. */
    private static final long TIMEOUT_SECONDS = 20;
    private static final String PREINITIALIZE = "-Dpolyglot.image-build-time.PreinitializeContexts=" + UpstreamLanguage.ID;
    private static final String SYNCHRONOUS = "-Dpolyglot.engine.BackgroundCompilation=false";
    private static final String DUMP_END = "--- end of thread dump ---";

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private record Child(Process process, Path outFile, Path errFile) {
        String out() throws IOException {
            return Files.readString(outFile, StandardCharsets.UTF_8);
        }

        String err() throws IOException {
            return Files.readString(errFile, StandardCharsets.UTF_8);
        }
    }

    /** {@link #main} in a child JVM with {@code flags}, and none of this JVM's engine options. */
    private Child start(String... flags) throws IOException {
        String java = ProcessHandle.current().info().command().orElseThrow();
        String modulePath = System.getProperty("jdk.module.path");
        assertNotNull("the tests run on the module path", modulePath);
        List<String> command = new ArrayList<>(List.of(java, "--module-path", modulePath, "--add-modules", "ALL-MODULE-PATH", "--enable-native-access=org.graalvm.truffle",
                        "--sun-misc-unsafe-memory-access=allow", "-Dpolyglot.log.engine.level=FINE"));
        command.addAll(List.of(flags));
        command.addAll(List.of("-m", "scheme.test/" + UpstreamPreinitializationDeadlockTest.class.getName()));
        Path out = folder.newFile().toPath();
        Path err = folder.newFile().toPath();
        Process process = new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        return new Child(process, out, err);
    }

    /** The control case: pre-initialization with background compilation works. */
    @Test
    public void preinitializationWithBackgroundCompilationFinishes() throws IOException, InterruptedException {
        Child child = start(PREINITIALIZE);
        child.process().getOutputStream().close();
        if (!child.process().waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            child.process().destroyForcibly().waitFor();
            throw new AssertionError("the control case timed out: " + child.out() + child.err());
        }
        assertEquals(child.err(), 0, child.process().exitValue());
        assertEquals("42\n", child.out());
        assertTrue(child.err(), child.err().contains("Successfully patched context of language: " + UpstreamLanguage.ID));
    }

    /**
     * With {@code BackgroundCompilation=false}, the child never gets past {@code Context.create}:
     * its thread dump shows the main thread waiting for a compilation in {@code Engine$ImplHolder}'s
     * class initializer, and the compiler thread waiting for that class initialization.
     */
    @Test
    public void preinitializationWithSynchronousCompilationDeadlocks() throws IOException, InterruptedException {
        Child child = start(PREINITIALIZE, SYNCHRONOUS);
        String dump;
        try {
            // Expected: like the control case, 42 and exit status 0.
            assertFalse("the child finished: " + child.out() + child.err(), child.process().waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            try (OutputStream in = child.process().getOutputStream()) {
                in.write("dump\n".getBytes(StandardCharsets.UTF_8));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            while (!child.out().contains(DUMP_END)) {
                assertTrue("no thread dump: " + child.out() + child.err(), System.nanoTime() < deadline);
                Thread.sleep(50);
            }
            dump = child.out();
        } finally {
            child.process().destroyForcibly().waitFor();
        }
        Map<String, String> threads = threads(dump);
        String main = threads.get("main");
        assertNotNull(dump, main);
        // The main thread: in Engine$ImplHolder.<clinit>, pre-initializing, waiting for the
        // runtime's first compilation (HotSpotTruffleRuntime.ensureInitialized).
        assertTrue(main, main.contains("at org.graalvm.polyglot.Engine$ImplHolder.<clinit>"));
        assertTrue(main, main.contains("at com.oracle.truffle.polyglot.PolyglotEngineImpl.preInitialize"));
        assertTrue(main, main.contains("at com.oracle.truffle.runtime.OptimizedTruffleRuntime.finishCompilation"));
        // The compiler thread: before it compiles anything, at Engine.getImpl, waiting for
        // ImplHolder's class initialization, which the main thread holds.
        String compiler = threads.entrySet().stream().filter(e -> e.getKey().startsWith("TruffleCompilerThread")).map(Map.Entry::getValue).findFirst().orElse(null);
        assertNotNull(dump, compiler);
        assertTrue(compiler, compiler.lines().skip(1).findFirst().orElse("").contains("at org.graalvm.polyglot.Engine.getImpl"));
        assertTrue(compiler, compiler.contains("at com.oracle.truffle.polyglot.EngineAccessor$EngineImpl.createPolyglotThreadScope"));
    }

    /** {@link #dumpThreads}'s output, by thread name. */
    private static Map<String, String> threads(String dump) {
        Map<String, String> threads = new HashMap<>();
        for (String thread : dump.substring(0, dump.indexOf(DUMP_END)).split("\n\n")) {
            if (thread.startsWith("\"")) {
                threads.put(thread.substring(1, thread.indexOf('"', 1)), thread);
            }
        }
        return threads;
    }

    /** Every thread's name, state and whole stack, on stdout. */
    private static void dumpThreads() {
        StringBuilder dump = new StringBuilder();
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            dump.append('"').append(entry.getKey().getName()).append("\" ").append(entry.getKey().getState()).append('\n');
            for (StackTraceElement frame : entry.getValue()) {
                dump.append("\tat ").append(frame.getClassName()).append('.').append(frame.getMethodName()).append('(').append(frame.getFileName()).append(':').append(frame.getLineNumber()).append(
                                ")\n");
            }
            dump.append('\n');
        }
        System.out.print(dump.append(DUMP_END).append('\n'));
        System.out.flush();
    }

    /**
     * The child: dumps its threads when a line arrives on stdin, and runs a program in the first
     * context, which is the pre-initialized one.
     */
    public static void main(String[] args) {
        Thread dumper = new Thread(() -> {
            try {
                if (new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine() != null) {
                    dumpThreads();
                }
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }, "dumper");
        dumper.setDaemon(true);
        dumper.start();
        try (Context context = Context.create(UpstreamLanguage.ID)) {
            System.out.println(context.eval(UpstreamLanguage.ID, "x"));
        }
    }
}
