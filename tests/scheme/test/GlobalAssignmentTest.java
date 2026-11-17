package scheme.test;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.junit.Test;

/**
 * {@code set!} of a global variable (design.md D7) from compiled code: the assignment is a store to
 * the cell, and only the first one invalidates the cell's assumption, which compiled code that read
 * the variable as a constant depends on. Each program runs
 * with the JIT compiling in the foreground after a few calls, so the procedures that assign and
 * read are compiled before, while and after the variables change.
 */
public class GlobalAssignmentTest {

    private static final String SCHEME = "scheme";

    /** What {@code program} writes, run compiled: in the foreground, each procedure after 10 calls. */
    private static String run(String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Context.Builder builder = Context.newBuilder(SCHEME).allowExperimentalOptions(true).out(out);
        builder.option("engine.Compilation", "true").option("engine.BackgroundCompilation", "false").option("engine.CompileImmediately", "false");
        builder.option("engine.MultiTier", "false").option("engine.SingleTierCompilationThreshold", "10");
        try (Context context = builder.build()) {
            context.eval(SCHEME, program);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void aCompiledLoopAssignsGlobals() {
        assertEquals("(200000 9999900000)", run("""
                        (import (scheme base) (scheme write))
                        (define count 0)
                        (define sum 0)
                        (define (bump! i) (set! count (+ count 1)) (set! sum (+ sum i)))
                        (define (run n) (do ((i 0 (+ i 1))) ((= i n)) (bump! i)))
                        (run 100000)
                        (run 100000)
                        (write (list count sum))
                        """));
    }

    @Test
    public void compiledReadersSeeAnAssignmentFromCompiledCode() {
        // get is compiled while limit has never been assigned, so it reads it as a constant; set-limit!
        // is compiled before its first call that assigns, so the assignment that invalidates get's
        // code is made from compiled code, and so are the later ones.
        assertEquals("(100 7 8 1000)", run("""
                        (import (scheme base) (scheme write))
                        (define limit 100)
                        (define (get) limit)
                        (define (set-limit! v assign?) (if assign? (set! limit v)) v)
                        (define (read-many n) (do ((i 0 (+ i 1)) (x 0 (get))) ((= i n) x)))
                        (define (assign-many n) (do ((i 0 (+ i 1))) ((= i n)) (set-limit! i #f)))
                        (define before (read-many 1000))
                        (assign-many 1000)
                        (set-limit! 7 #t)
                        (define after (read-many 1000))
                        (set-limit! 8 #t)
                        (define again (read-many 1000))
                        (do ((i 0 (+ i 1))) ((= i 1001)) (set-limit! i #t))
                        (write (list before after again (read-many 1000)))
                        """));
    }

    @Test
    public void assigningAnUnboundGlobalFromCompiledCodeIsAnError() {
        // In the interaction environment, set! of a variable nothing has defined is an error when it
        // runs, from compiled code too.
        assertEquals("(caught assigned 999)", run("""
                        (define (try v) (guard (e (#t 'caught)) (set! never-defined v) 'assigned))
                        (define (many n) (do ((i 0 (+ i 1)) (r #f (try i))) ((= i n) r)))
                        (define unbound (many 1000))
                        (define never-defined #f)
                        (write (list unbound (many 1000) never-defined))
                        """));
    }
}
