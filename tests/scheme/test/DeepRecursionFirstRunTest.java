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
 * M9-01: a deep recursion's first run deoptimizes O(1) frames. A procedure compiled while its first
 * recursion descends has never run what follows its recursive call (the base case's branch, the
 * {@code +} or {@code cons} after the call, the return type's profile): the first return
 * deoptimizes and invalidates that code, and every compiled frame still on the stack deoptimizes,
 * one by one, as it is returned to (#170: natively 25 to 40 µs each). So a procedure isn't compiled
 * while many of its frames wait in calls from a site that hasn't returned yet (design.md
 * "Stack-depth spill").
 *
 * <p>The compiler runs synchronously, once a root has been called 100 times: mid-descent. The
 * deoptimized frames are counted from the engine's log ({@code engine.TraceCompilation} logs an
 * {@code opt deopt} for each).
 */
public class DeepRecursionFirstRunTest {

    private static final String SCHEME = "scheme";

    private static final String COUNT = "(define (count n d) (if (= d 0) n (+ 1 (count n (- d 1)))))";
    private static final String BUILD = "(define (build n) (if (= n 0) '() (cons n (build (- n 1)))))";
    private static final String EVEN_ODD = "(define (ev n) (if (= n 0) 0 (+ 1 (od (- n 1)))))" +
                    "(define (od n) (if (= n 0) 0 (+ 1 (ev (- n 1)))))";

    /** The engine's log of compilations, from a context that compiles synchronously after 100 calls. */
    private static final class Run implements AutoCloseable {
        final Path log;
        final Context context;

        Run(String... options) throws IOException {
            log = Files.createTempFile("deep-recursion", ".log");
            Context.Builder builder = Context.newBuilder(SCHEME).allowExperimentalOptions(true).option("log.file", log.toString()).out(new ByteArrayOutputStream());
            for (int i = 0; i < options.length; i += 2) {
                builder.option(options[i], options[i + 1]);
            }
            builder.option("engine.Compilation", "true").option("engine.CompileImmediately", "false").option("engine.BackgroundCompilation", "false");
            builder.option("engine.MultiTier", "false").option("engine.SingleTierCompilationThreshold", "100").option("engine.TraceCompilation", "true");
            Context built;
            try {
                built = builder.build();
            } catch (IllegalArgumentException e) {
                Files.delete(log);
                Assume.assumeNoException("no optimizing Truffle runtime", e);
                throw e;
            }
            context = built;
        }

        String log() throws IOException {
            return Files.readString(log);
        }

        @Override
        public void close() throws IOException {
            context.close();
            Files.delete(log);
        }
    }

    private static int count(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static Pattern event(String event, String name) {
        return Pattern.compile(event + "\\s+engine=\\d+\\s+id=\\d+\\s+" + Pattern.quote(name) + "\\s");
    }

    /** At most this many of a procedure's frames deoptimize in a first run, however deep. */
    private static final int FEW = 4;

    @Test
    public void aFirstDescentDeoptimizesFewFrames() throws IOException {
        // Before M9-01, about 19,900 frames of count deoptimized: every one compiled after the 100th
        // call (all but those that spilled).
        try (Run run = new Run()) {
            assertEquals(20000L, run.context.eval(SCHEME, COUNT + "(count 0 20000)").asLong());
            String log = run.log();
            int deopts = count(event("opt deopt", "count"), log);
            assertTrue(deopts + " deoptimized frames of count:\n" + tail(log), deopts <= FEW);
        }
    }

    @Test
    public void aListBuiltByRecursionDeoptimizesFewFrames() throws IOException {
        try (Run run = new Run()) {
            assertEquals(20000L, run.context.eval(SCHEME, BUILD + "(length (build 20000))").asLong());
            String log = run.log();
            int deopts = count(event("opt deopt", "build"), log);
            assertTrue(deopts + " deoptimized frames of build:\n" + tail(log), deopts <= FEW);
        }
    }

    @Test
    public void mutualRecursionDeoptimizesFewFrames() throws IOException {
        try (Run run = new Run()) {
            assertEquals(20000L, run.context.eval(SCHEME, EVEN_ODD + "(ev 20000)").asLong());
            String log = run.log();
            int deopts = count(event("opt deopt", "ev"), log) + count(event("opt deopt", "od"), log);
            assertTrue(deopts + " deoptimized frames of ev and od:\n" + tail(log), deopts <= 2 * FEW);
        }
    }

    @Test
    public void aSpilledDescentDeoptimizesFewFrames() throws IOException {
        // A spill every 50 nested calls: each takes the frames waiting in count's site off the
        // Java stack, and the descent goes on from the frame that spilled, on a fresh one, where
        // count mustn't be compiled before it is deep again.
        try (Run run = new Run("scheme.SpillDepth", "50")) {
            assertEquals(3000L, run.context.eval(SCHEME, COUNT + "(count 0 3000)").asLong());
            String log = run.log();
            int deopts = count(event("opt deopt", "count"), log);
            assertTrue(deopts + " deoptimized frames of count:\n" + tail(log), deopts <= FEW);
        }
    }

    @Test
    public void aLoopAtTheBottomOfAFirstDescentIsCompiled() throws IOException {
        // f isn't compiled while 20 of its frames wait in its first descent, but the loop at the
        // bottom is, by on-stack replacement: that leaves no compiled frame of f on the stack.
        try (Run run = new Run()) {
            String f = "(define (f n) (if (= n 0) (let loop ((i 0) (s 0)) (if (< i 300000) (loop (+ i 1) (+ s i)) s)) (+ 1 (f (- n 1)))))";
            assertEquals(44999850020L, run.context.eval(SCHEME, f + "(f 20)").asLong());
            String log = run.log();
            assertTrue(tail(log), Pattern.compile("opt done\\s+engine=\\d+\\s+id=\\d+\\s+f<OSR").matcher(log).find());
            assertTrue(tail(log), count(event("opt deopt", "f"), log) <= FEW);
        }
    }

    @Test
    public void theNextDeepRecursionDoesNotDeoptimize() throws IOException {
        // Not compiling a procedure during its first descent doesn't keep it from being compiled:
        // after it, the next calls compile it, and its compiled code runs the next deep recursion
        // without deoptimizing.
        try (Run run = new Run()) {
            run.context.eval(SCHEME, COUNT + "(count 0 3000)");
            Pattern done = event("opt done", "count");
            run.context.eval(SCHEME, "(do ((i 0 (+ i 1))) ((= i 200)) (count 0 10))");
            String warm = run.log();
            assertTrue(tail(warm), done.matcher(warm).find());
            int before = count(event("opt deopt", "count"), warm);
            assertEquals(3000L, run.context.eval(SCHEME, "(count 0 3000)").asLong());
            String log = run.log();
            assertEquals(tail(log), before, count(event("opt deopt", "count"), log));
        }
    }

    /** A log's events but the deoptimized frames, and its end, for a failure's message. */
    private static String tail(String log) {
        StringBuilder events = new StringBuilder();
        for (String line : log.split("\n")) {
            if (!line.contains("opt deopt")) {
                events.append(line, 0, Math.min(line.length(), 160)).append('\n');
            }
        }
        return events + (log.length() <= 2000 ? log : "..." + log.substring(log.length() - 2000));
    }
}
