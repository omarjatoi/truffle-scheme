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
 * M11-04: a continuation root that resumes into a loop deoptimizes once, at the loop's first exit,
 * and only if it was compiled before the loop had ever exited. A procedure whose loop captures a
 * continuation on every iteration runs each iteration but the first in a continuation root, one
 * per resume, so that root is compiled (after 100 resumes) while the loop's exit is still a branch
 * the bytecode's profile has never seen taken: the compiled code has a
 * {@code transferToInterpreterAndInvalidate} there, and the exit, at the end of the loop, takes it.
 * The root's own return-type profile, which had only seen the {@code ContinuationResult} of a
 * frame that suspends again, changes at the same exit too. Both are profiles that stabilize once:
 * the branch profile belongs to the bytecode, which the procedure and all its continuation roots
 * share, and the recompiled root keeps the exit.
 *
 * <p>HotSpot reports the trap ({@code -Xlog:deoptimization=debug}) at the last frame state before
 * the branch, which is the loop's condition: {@code LoopHead.releaseOSRParent} at its return (bci
 * 57), and {@code FrameWithoutBoxing.unsafeClear} when {@code releaseOSRParent} does nothing. That
 * is what made it look like {@code releaseOSRParent}'s (#213's follow-up). A loop that has exited
 * once before the root is compiled never deoptimizes it.
 *
 * <p>The compiler runs synchronously, after 100 calls, at its last tier. An invalidated compilation
 * shows in the engine's log as an {@code opt deopt} with {@code Invalidated true}.
 */
public class ResumedLoopRecompilationTest {

    private static final String SCHEME = "scheme";

    private static final Pattern INVALIDATED = Pattern.compile("opt deopt\\s+engine=\\d+\\s+id=\\d+\\s+(\\S+)\\s+\\|Invalidated\\s+true\\|");

    private static final Pattern RESUMED_GO = Pattern.compile("opt done\\s+engine=\\d+\\s+id=\\d+\\s+go\\(resume_bci=\\d+\\)\\s");

    /**
     * {@code go}'s loop captures a continuation on every iteration ({@code k} escapes, so it is a
     * full continuation), which the root prompt resumes into the loop.
     */
    private static final String GO = "(define saved #f)" +
                    "(define (one k) (set! saved k) (k 1))" +
                    "(define (go n acc) (if (= n 0) acc (go (- n 1) (+ acc (call/cc one)))))";

    /** The engine's log of compilations, from a context that compiles synchronously. */
    private static String log(String program, long expected) throws IOException {
        Path log = Files.createTempFile("resumed-loop", ".log");
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

    /** The compilations the log says were invalidated, by name. */
    private static String invalidated(String log) {
        StringBuilder names = new StringBuilder();
        Matcher m = INVALIDATED.matcher(log);
        while (m.find()) {
            names.append(m.group(1)).append('\n');
        }
        return names.toString();
    }

    @Test
    public void aResumedLoopThatHasExitedIsNotInvalidated() throws IOException {
        String log = log(GO + "(go 1 0)(go 5000 0)", 5000L);
        assertTrue(log, RESUMED_GO.matcher(log).find());
        assertEquals(log, "", invalidated(log));
    }

    @Test
    public void aResumedLoopIsInvalidatedOnceByItsFirstExit() throws IOException {
        // The same program without (go 1 0): the loop's only exit is its last iteration's, after
        // the continuation root that resumes into it was compiled.
        String log = log(GO + "(go 5000 0)", 5000L);
        assertTrue(log, RESUMED_GO.matcher(log).find());
        assertTrue(log, invalidated(log).matches("go\\(resume_bci=\\d+\\)\n"));
    }
}
