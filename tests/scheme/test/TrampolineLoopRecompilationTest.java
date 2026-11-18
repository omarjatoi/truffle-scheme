package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.graalvm.polyglot.Context;
import org.junit.Assume;
import org.junit.Test;

/**
 * M12-12: compiled code whose trampoline loop runs a procedure that reads an assigned variable, or
 * a captured one, isn't invalidated the first time it gets there. ecraven's {@code parsing}
 * recompiled its large roots ({@code parse-datum}, {@code parse-list2}, ...) after their first
 * compilations, about twice on the JVM and 8 times in the native launcher, each time a
 * {@code NullCheckException} deoptimization at {@code BoxRef} or {@code LoadCaptured}.
 *
 * <p>The cause was the cast of the operand those operations' one specialization took
 * ({@code (Box) operand}, {@code (SClosure) operand}): Graal compiles a cast that no profile says
 * is never null into a branch for null, and the field read after it then has, on that branch, a
 * null check that can only fail: a guard whose condition is the constant {@code true}. That branch
 * never runs, but Graal's speculative guard movement takes a constant condition for loop-invariant
 * and moves the guard out of the loop it is in, the trampoline's loop of tail calls, to before
 * the loop, where it fails at once. The speculation log then keeps the next compilation from
 * moving it, but the log belongs to a call target, so each root that inlines such a loop paid one
 * deoptimization (design.md D17, "The native launcher's steady state"). The operations now check
 * their operand's type ({@code instanceof}, with a fallback that the compiled code never reaches),
 * so the compiled code has no branch for null.
 *
 * <p>The compiler runs synchronously, after 100 calls, at its last tier. An invalidated compilation
 * shows in the engine's log as an {@code opt deopt} with {@code Invalidated true}.
 */
public class TrampolineLoopRecompilationTest {

    private static final String SCHEME = "scheme";

    private static final Pattern INVALIDATED = Pattern.compile("opt deopt\\s+engine=\\d+\\s+id=\\d+\\s+(\\S+)\\s+\\|Invalidated\\s+true\\|");

    /** The engine's log of compilations, from a context that compiles synchronously. */
    private static String log(String program, long expected) throws IOException {
        Path log = Files.createTempFile("trampoline-loop", ".log");
        try {
            Context.Builder builder = Context.newBuilder(SCHEME).allowExperimentalOptions(true).option("log.file", log.toString()).out(new ByteArrayOutputStream());
            builder.option("engine.Compilation", "true").option("engine.CompileImmediately", "false").option("engine.BackgroundCompilation", "false");
            builder.option("engine.MultiTier", "false").option("engine.SingleTierCompilationThreshold", "100");
            builder.option("engine.TraceCompilation", "true");
            Context context;
            try {
                context = builder.build();
            } catch (IllegalArgumentException e) {
                Assume.assumeNoException("no optimizing Truffle runtime", e);
                throw e;
            }
            try (context) {
                assertEquals(expected, context.eval(SCHEME, program).asLong());
            }
            return Files.readString(log);
        } finally {
            Files.delete(log);
        }
    }

    /**
     * The compilations the log says were invalidated, by name, continuation roots
     * ({@code name(resume_bci=n)}) included.
     */
    private static String invalidated(String log) {
        StringBuilder names = new StringBuilder();
        Matcher m = INVALIDATED.matcher(log);
        while (m.find()) {
            names.append(m.group(1)).append('\n');
        }
        return names.toString();
    }

    /**
     * {@code (go 1 0)} before {@code (go 5000 0)}: under torture {@code go}'s loop runs in a
     * continuation root, which, compiled before the loop has ever exited, deoptimizes once at its
     * first exit (ResumedLoopRecompilationTest). Taking the exit once first leaves this test's
     * cause alone in the log.
     */
    private static final String GO = "(define (go i acc) (if (= i 0) acc (go (- i 1) (r 3))))" +
                    "(go 1 0)" +
                    "(go 5000 0)";

    @Test
    public void aBoxReadInTheTrampolinesLoopDoesNotInvalidateTheRoot() throws IOException {
        // r's call of start returns step1's tail call, which r's trampoline performs; step1's
        // tail call of step2, and step2's of step1, run in the trampoline's loop, where step1
        // calls bump, which reads and assigns n, a variable in a box that bump's closure captures.
        String program = "(define (make-counter)" +
                        "  (let ((n 0))" +
                        "    (define (get) n)" +
                        "    (define (bump) (set! n (+ n 1)) n)" +
                        "    (define (step2 k) (if (> k 0) (step1 (- k 1)) (get)))" +
                        "    (define (step1 k) (bump) (step2 k))" +
                        "    (define (start k) (step1 k))" +
                        "    start))" +
                        "(define c (make-counter))" +
                        "(define (r k) (+ 1 (c k)))" +
                        GO;
        String log = log(program, 20005L);
        assertTrue(log, Pattern.compile("opt done\\s+engine=\\d+\\s+id=\\d+\\s+r\\s").matcher(log).find());
        assertEquals(log, "", invalidated(log));
    }
}
