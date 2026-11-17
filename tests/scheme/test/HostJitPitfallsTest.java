package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.graalvm.polyglot.Context;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import scheme.compiler.Expander;
import scheme.compiler.SyntacticEnvironment;
import scheme.runtime.GlobalEnvironment;
import scheme.runtime.value.SymbolTable;

/**
 * M9-04: control transfers and driver loops that the host JIT (HotSpot, with Graal as its
 * compiler) compiles from profiles it can get wrong, as it did for the trampoline's loop (#167)
 * and for a full continuation's throw (#172, {@link ApplyContinuationTest}).
 *
 * <ul>
 * <li>Invoking an escape-only k ({@code %call/ec}) returns an ESCAPE {@code Capture} that travels up
 * to its prompt, as an APPLY one does, instead of throwing a Java exception to it (design.md
 * "Continuations"). Thrown from inside a loop through the trampoline's loop, the exception
 * deoptimized the host-compiled interpreter again and again
 * ({@code not_compiled_exception_handler} at {@code OptimizedDirectCallNode.call}).</li>
 * <li>The root prompt's driver loop, a Java loop in a root that runs once, runs a deep
 * recursion's whole return path while other prompts' drivers, which loop once or twice, make its
 * host profile: HotSpot must still compile it by on-stack replacement.</li>
 * </ul>
 */
public class HostJitPitfallsTest {

    private static final String SCHEME = "scheme";

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static String output(String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            context.eval(SCHEME, program);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** The result of a child JVM that runs the launcher on a program. */
    private record Run(int exit, String out, String err) {
    }

    /** Runs {@code program} in a child JVM with {@code jvmArguments}, the launcher's way. */
    private Run launch(String program, List<String> jvmArguments) throws IOException, InterruptedException {
        Path file = folder.newFile("program.scm").toPath();
        Files.writeString(file, program, StandardCharsets.UTF_8);
        String java = ProcessHandle.current().info().command().orElseThrow();
        String modulePath = System.getProperty("jdk.module.path");
        assertNotNull("the tests run on the module path", modulePath);
        Path out = folder.newFile().toPath();
        Path err = folder.newFile().toPath();
        List<String> command = new ArrayList<>(List.of(java));
        command.addAll(jvmArguments);
        command.addAll(List.of("--module-path", modulePath, "--enable-native-access=org.graalvm.truffle", "--sun-misc-unsafe-memory-access=allow",
                        "-m", "scheme.launcher/scheme.launcher.SchemeMain", file.toString()));
        Process process = new ProcessBuilder(command).directory(folder.getRoot()).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        process.getOutputStream().close();
        assertTrue("the program ends", process.waitFor(120, TimeUnit.SECONDS));
        return new Run(process.exitValue(), Files.readString(out, StandardCharsets.UTF_8), Files.readString(err, StandardCharsets.UTF_8));
    }

    /**
     * {@code puzzle}'s shape, with k reached through two tail calls: k is invoked from a frame the
     * site's trampoline loop called, inside a {@code do} loop's body, a lambda that is only called
     * (so k is escape-only). With the interpreter only, and every exception the JVM throws logged:
     * none is the runtime's. (Thrown, the escape deoptimized {@code continueAt} 36 times in a
     * {@code puzzle} built this way.)
     */
    private static final String ESCAPE_IN_A_LOOP = """
                    (import (scheme base) (scheme write))
                    (define (search v x)
                      (call-with-current-continuation
                       (lambda (return)
                         (letrec* ((found-at (lambda (i) (return i)))
                                   (found (lambda (i) (found-at i))))
                           (do ((i 0 (+ i 1))) ((= i (vector-length v)) #f)
                             (if (= (vector-ref v i) x) (found i))
                             (if (= (vector-ref v i) (- x)) (return (- i))))))))
                    (define v (make-vector 20 0))
                    (vector-set! v 15 7)
                    (vector-set! v 12 -8)
                    (define (run n acc)
                      (if (= n 0) acc (run (- n 1) (+ acc (search v 7) (search v 8)))))
                    (write (run 2000 0))
                    """;

    @Test
    public void theProgramEscapes() {
        // The compiler proves k escape-only, so the program below exercises %call/ec's escape.
        String ast = Expander.show(new SymbolTable(), new GlobalEnvironment(), new SyntacticEnvironment(), ESCAPE_IN_A_LOOP, true);
        assertTrue(ast, ast.contains("%call/ec") && !ast.contains("call-with-current-continuation"));
    }

    @Test
    public void invokingAnEscapeOnlyContinuationThrowsNoJavaException() throws IOException, InterruptedException {
        Path exceptions = folder.newFile().toPath();
        Run run = launch(ESCAPE_IN_A_LOOP, List.of("-Xlog:exceptions=info:file=" + exceptions, "-Dpolyglot.engine.AllowExperimentalOptions=true",
                        "-Dpolyglot.engine.Compilation=false"));
        assertEquals(run.err(), 0, run.exit());
        assertEquals("6000", run.out());
        String thrown = Files.readAllLines(exceptions, StandardCharsets.UTF_8).stream().filter(line -> line.contains("<a 'scheme/")).collect(Collectors.joining("\n"));
        assertEquals("", thrown);
    }

    /**
     * A deep recursion that spills to the heap (every 1,000 calls), so the root prompt's driver loop
     * resumes each of its frames, in the one call of {@code PromptDriver.run} of a root that runs
     * once; each frame it resumes calls an escape-only call/cc, whose own driver returns at once.
     * HotSpot compiles the root prompt's loop by on-stack replacement: at least one OSR compilation
     * of {@code PromptDriver.run} completes, none is skipped because the profile of the short runs
     * left the loop out (#167's "OSR entry point wasn't parsed"), and the driver is recompiled a
     * bounded number of times (3 to 8 when this was written). Other skips are allowed: HotSpot
     * cancels a queued compilation now and then ("Compilation cancelled"), and asks again.
     */
    @Test
    public void theRootPromptsDriverLoopIsCompiledByOnStackReplacement() throws IOException, InterruptedException {
        Run run = launch("""
                        (import (scheme base) (scheme process-context))
                        (define (ec x) (call/cc (lambda (k) (if (< x 0) (k 0) x))))
                        (define (count n) (if (= n 0) 0 (+ 1 (count (- n 1)) (ec n))))
                        (define (rounds i)
                          (or (= i 0) (and (= (count 300000) 45000450000) (rounds (- i 1)))))
                        (exit (rounds 4))
                        """, List.of("-XX:+PrintCompilation", "-Dpolyglot.engine.AllowExperimentalOptions=true", "-Dpolyglot.scheme.SpillDepth=1000"));
        assertEquals(run.err(), 0, run.exit());
        List<String> driver = run.out().lines().filter(line -> line.contains("scheme.runtime.PromptDriver::run")).toList();
        String log = String.join("\n", driver);
        assertEquals(log, List.of(), driver.stream().filter(line -> line.contains("COMPILE SKIPPED: OSR entry point wasn't parsed")).toList());
        // -XX:+PrintCompilation prints a compilation's id on its line ("<time> <id> % ...", % for OSR),
        // and again with "COMPILE SKIPPED: <reason>" if it doesn't complete.
        Set<String> osr = new HashSet<>();
        Set<String> skipped = new HashSet<>();
        for (String line : driver) {
            Matcher m = COMPILATION.matcher(line);
            if (!m.find()) {
                continue;
            }
            if (line.contains("COMPILE SKIPPED")) {
                skipped.add(m.group(1));
            } else if (m.group(2) != null) {
                osr.add(m.group(1));
            }
        }
        osr.removeAll(skipped);
        assertTrue("no OSR compilation of the driver completed:\n" + log, !osr.isEmpty());
        assertTrue(log, driver.stream().filter(line -> line.contains("made not entrant")).count() <= 16);
    }

    /** A line of {@code -XX:+PrintCompilation}: its time, its compilation id, and % for OSR. */
    private static final Pattern COMPILATION = Pattern.compile("^\\s*\\d+\\s+(\\d+)\\s+(%)?");

    // ------------------------------------------------------------------------------------------
    // Semantics an escape keeps as a returned request

    @Test
    public void escapesFromSpilledFrames() {
        // From a recursion deep enough to spill, whose frames and the inner escape's prompt are
        // then pending in the root prompt, to the inner escape and to the outer one; and once the
        // inner one has returned, from frames resumed after it.
        String program = """
                        (import (scheme base) (scheme write))
                        (define (deep i k) (if (= i 100000) (k (+ i 1)) (+ 1 (deep (+ i 1) k))))
                        (define (deep-then i k) (if (= i 100000) 0 (+ 1 (deep-then (+ i 1) k) (if (= i 50000) (k 'late) 0))))
                        (write (list (call/cc (lambda (k) (+ 1 (call/cc (lambda (j) (+ 1 (deep 0 j)))))))
                                     (call/cc (lambda (k) (+ 1 (deep 0 k))))
                                     (call/cc (lambda (k) (deep-then 0 k)))))
                        """;
        String ast = Expander.show(new SymbolTable(), new GlobalEnvironment(), new SyntacticEnvironment(), program, true);
        assertTrue(ast, !ast.contains("(call/cc") && !ast.contains("call-with-current-continuation"));
        assertEquals("(100002 100001 late)", output(program));
    }
}
