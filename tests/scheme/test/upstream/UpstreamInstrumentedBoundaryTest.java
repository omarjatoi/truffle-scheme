package scheme.test.upstream;

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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * Graal 25.5 bug: with {@code compiler.InstrumentBoundaries}, every Truffle compilation that
 * instruments a boundary fails in libgraal, HotSpot's default Graal (docs/upstream.md,
 * "Instrumented compilations fail in libgraal"): lowering the read of the instrumentation's counter
 * table, libgraal's constant reflection is handed a {@code SnippetObjectConstant}, and throws a
 * {@code ClassCastException}. Graal compiled for HotSpot ({@code -XX:-UseJVMCINativeLibrary})
 * compiles the same root and prints its profile. Our workaround: {@code bazel-bin/scheme} runs
 * Graal compiled for HotSpot when it is given the option ({@code launcher_vm_args},
 * tests/harness/harness.py), and {@code SchemeMain} refuses the option with libgraal and in the
 * native launcher.
 *
 * <p>Each case runs {@link #main} in a child JVM on this one's module path, with neither this JVM's
 * engine options nor its compilations: a JVM's Truffle compiler takes the options of the first
 * engine that compiles, so the instrumented compilation has to be the JVM's first. Inverted, like
 * every test in this package: it passes while the bug is there. When a Graal upgrade fixes the
 * bug, it fails; then remove the workaround, this test and its entry in docs/upstream.md.
 */
public class UpstreamInstrumentedBoundaryTest {

    private static final LanguageReference<UpstreamLanguage> LANGUAGE = LanguageReference.create(UpstreamLanguage.class);

    /** The child JVMs' compilers: libgraal (HotSpot's default) and Graal compiled for HotSpot. */
    private static final String LIBGRAAL = "-XX:+UseJVMCINativeLibrary";
    private static final String JARGRAAL = "-XX:-UseJVMCINativeLibrary";

    /** How long a child gets: Graal compiled for HotSpot starts in the bytecode interpreter. */
    private static final long TIMEOUT_SECONDS = 120;

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    /** Adds up the lengths of the numbers below {@code n}, each one in a call to a boundary. */
    static final class BoundaryLoopRoot extends RootNode {

        BoundaryLoopRoot(TruffleLanguage<?> language) {
            super(language);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            int n = (Integer) frame.getArguments()[0];
            int sum = 0;
            for (int i = 0; i < n; i++) {
                sum += length(i);
            }
            return sum;
        }

        @TruffleBoundary
        static int length(int i) {
            return Integer.toString(i).length();
        }

        @Override
        public String getName() {
            return "boundary-loop";
        }

        @Override
        public String toString() {
            return getName();
        }
    }

    /** {@link #main} in a child JVM with {@code flags}: what it printed. */
    private String child(String... flags) throws IOException, InterruptedException {
        String java = ProcessHandle.current().info().command().orElseThrow();
        String modulePath = System.getProperty("jdk.module.path");
        assertNotNull("the tests run on the module path", modulePath);
        List<String> command = new ArrayList<>(List.of(java));
        command.addAll(List.of(flags));
        command.addAll(List.of("--module-path", modulePath, "--add-modules", "ALL-MODULE-PATH", "--enable-native-access=org.graalvm.truffle", "--sun-misc-unsafe-memory-access=allow"));
        command.addAll(List.of("-m", "scheme.test/" + UpstreamInstrumentedBoundaryTest.class.getName()));
        Path out = folder.newFile().toPath();
        Path err = folder.newFile().toPath();
        Process process = new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        process.getOutputStream().close();
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor();
            throw new AssertionError("the child timed out: " + Files.readString(out, StandardCharsets.UTF_8) + Files.readString(err, StandardCharsets.UTF_8));
        }
        String log = Files.readString(out, StandardCharsets.UTF_8);
        assertEquals(log + Files.readString(err, StandardCharsets.UTF_8), 0, process.exitValue());
        return log;
    }

    @Test
    public void anInstrumentedCompilationFailsInLibgraal() throws IOException, InterruptedException {
        String log = child(LIBGRAAL);
        // Expected: "opt done", and the boundary's count in the profile, as in the control case.
        // Actual: the compilation fails, and the profile is empty.
        assertTrue(log, log.contains("opt failed"));
        assertTrue(log, log.contains("Reason: java.lang.ClassCastException: jdk.graal.compiler.hotspot.SnippetObjectConstant cannot be cast to jdk.vm.ci.hotspot.HotSpotObjectConstantImpl"));
        assertFalse(log, log.contains("opt done"));
        assertFalse(log, log.contains("Execution profile"));
    }

    /** Control: Graal compiled for HotSpot compiles the same root, and profiles its boundary. */
    @Test
    public void graalCompiledForHotSpotCompilesItAndProfilesTheBoundary() throws IOException, InterruptedException {
        String log = child(JARGRAAL);
        assertTrue(log, log.contains("opt done"));
        assertFalse(log, log.contains("opt failed"));
        assertTrue(log, log.contains("Execution profile"));
        assertTrue(log, log.contains("BoundaryLoopRoot.execute"));
    }

    /**
     * The child: compiles {@link BoundaryLoopRoot} with its boundary instrumented, and prints the
     * engine's log: the compilation ("opt done", or "opt failed" and the failure) and the profile
     * the instrumentation prints as the engine closes.
     */
    public static void main(String[] args) throws IOException {
        Path log = Files.createTempFile("upstream-instrumented-boundary", ".log");
        try {
            Context.Builder builder = Context.newBuilder(UpstreamLanguage.ID).allowExperimentalOptions(true).option("log.file", log.toString()).out(new ByteArrayOutputStream()).err(
                            new ByteArrayOutputStream());
            builder.option("engine.CompileImmediately", "false").option("engine.BackgroundCompilation", "false");
            builder.option("engine.MultiTier", "false").option("engine.SingleTierCompilationThreshold", "100");
            builder.option("engine.TraceCompilation", "true").option("engine.CompilationFailureAction", "Print");
            builder.option("compiler.InstrumentBoundaries", "true");
            try (Context context = builder.build()) {
                context.initialize(UpstreamLanguage.ID);
                context.enter();
                try {
                    CallTarget target = new BoundaryLoopRoot(LANGUAGE.get(null)).getCallTarget();
                    for (int i = 0; i < 1000; i++) {
                        if (!Integer.valueOf(10).equals(target.call(10))) {
                            throw new AssertionError("boundary-loop(10) isn't 10");
                        }
                    }
                } finally {
                    context.leave();
                }
            }
            System.out.print(Files.readString(log));
        } finally {
            Files.delete(log);
        }
    }
}
