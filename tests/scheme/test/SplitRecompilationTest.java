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
 * M10-06: compiled code that inlines a string comparison isn't invalidated by its first comparison.
 * In ecraven's {@code scheme}, each of 8 splits of {@code gen-combination-2}'s lambda deoptimized
 * once after its first compilation, by an uncommon trap (#205). The trap was a null check that
 * Graal's speculative guard movement had hoisted out of {@code string<?}'s loop: the read of a
 * wide string's code points, whose null check, loop-invariant, ran before the loop for Latin-1
 * strings too. The speculation log that then keeps the guard in the loop belongs to a call target,
 * and a split is a call target of its own, so every split paid the deoptimization once (design.md
 * "Representations chosen outside loops").
 *
 * <p>The compiler runs synchronously, after 100 calls, at its last tier. An invalidated compilation
 * shows in the engine's log as an {@code opt deopt} with {@code Invalidated true}.
 */
public class SplitRecompilationTest {

    private static final String SCHEME = "scheme";

    /** The engine's log of compilations and splits, from a context that compiles synchronously. */
    private static final class Run implements AutoCloseable {
        final Path log;
        final Context context;

        Run(String... options) throws IOException {
            log = Files.createTempFile("split-recompilation", ".log");
            Context.Builder builder = Context.newBuilder(SCHEME).allowExperimentalOptions(true).option("log.file", log.toString()).out(new ByteArrayOutputStream());
            for (int i = 0; i < options.length; i += 2) {
                builder.option(options[i], options[i + 1]);
            }
            builder.option("engine.Compilation", "true").option("engine.CompileImmediately", "false").option("engine.BackgroundCompilation", "false");
            builder.option("engine.MultiTier", "false").option("engine.SingleTierCompilationThreshold", "100");
            builder.option("engine.TraceCompilation", "true").option("engine.TraceSplitting", "true");
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

    private static final Pattern INVALIDATED = Pattern.compile("opt deopt\\s+engine=\\d+\\s+id=\\d+\\s+(\\S+(?: <split-\\d+>)?)\\s+\\|Invalidated\\s+true\\|");

    /**
     * The compilations the log says were invalidated, by name, continuation roots
     * ({@code name(resume_bci=n)}) included: under torture every non-tail call suspends and
     * resumes its frame in one.
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
     * {@code (go 1 0)}, before the programs' {@code (go 5000 0)}: under torture {@code go}'s loop
     * runs in a continuation root, one iteration per resume, which is compiled before the loop
     * has ever exited, and its first exit then deoptimizes it once (a branch never taken, and the
     * root's return type), as {@code ResumedLoopRecompilationTest} shows (M11-04). Taking the exit
     * once first leaves this test's cause alone in the log.
     */
    private static final String EXIT_GO_ONCE = "(go 1 0)";

    private static final String WORDS = "(define words '(\"one\" \"two\" \"three\" \"four\" \"five\" \"six\" \"seven\" \"eight\"))";

    @Test
    public void aComparisonOfLatin1StringsDoesNotInvalidateTheCodeThatInlinesIt() throws IOException {
        try (Run run = new Run()) {
            String program = WORDS + "(define (lt a b) (string<? a b))" +
                            "(define (count-ascending l k) (if (pair? (cdr l)) (count-ascending (cdr l) (if (lt (car l) (cadr l)) (+ k 1) k)) k))" +
                            "(define (go n acc) (if (= n 0) acc (go (- n 1) (count-ascending words acc))))" +
                            EXIT_GO_ONCE + "(go 5000 0)";
            assertEquals(10000L, run.context.eval(SCHEME, program).asLong());
            String log = run.log();
            assertTrue(log, Pattern.compile("opt done\\s+engine=\\d+\\s+id=\\d+\\s+count-ascending\\s").matcher(log).find());
            assertEquals(log, "", invalidated(log));
        }
    }

    @Test
    public void theSplitsOfAClosureThatComparesStringsAreNotInvalidated() throws IOException {
        // ecraven's scheme's shape: the closures a small interpreter builds, which call each other.
        // The sites in gen-combination-2's lambda call more procedures than their caches hold, so
        // Truffle splits the lambda for each of its callers. Without inlining, each split is
        // compiled on its own, as most of them were in ecraven's scheme, where their callers were
        // too large to inline them; the comparison runs in place in the split's code.
        String program = "(define (gen-const x) (lambda (rte) x))" +
                        "(define (gen-ref i) (lambda (rte) (vector-ref rte i)))" +
                        "(define (gen-car i) (lambda (rte) (car (vector-ref rte i))))" +
                        "(define (gen-cadr i) (lambda (rte) (car (cdr (vector-ref rte i)))))" +
                        "(define (gen-string i) (lambda (rte) (symbol->string (vector-ref rte i))))" +
                        "(define (gen-combination-2 oper arg1 arg2) (lambda (rte) ((oper rte) (arg1 rte) (arg2 rte))))" +
                        "(define (gen-if c t e) (lambda (rte) (if (c rte) (t rte) (e rte))))" +
                        "(define (gen-and a b) (lambda (rte) (and (a rte) (b rte))))" +
                        "(define lt1 (gen-combination-2 (gen-const string<?) (gen-ref 0) (gen-ref 1)))" +
                        "(define lt2 (gen-combination-2 (gen-const string<?) (gen-car 3) (gen-cadr 3)))" +
                        "(define lt3 (gen-combination-2 (gen-const string<?) (gen-string 4) (gen-ref 1)))" +
                        "(define gt1 (gen-combination-2 (gen-const string>?) (gen-ref 0) (gen-car 3)))" +
                        "(define gt2 (gen-combination-2 (gen-const string>?) (gen-cadr 3) (gen-string 4)))" +
                        "(define add (gen-combination-2 (gen-const +) (gen-ref 2) (gen-const 1)))" +
                        "(define sub (gen-combination-2 (gen-const -) (gen-ref 2) (gen-const 1)))" +
                        "(define body (gen-if (gen-and lt1 lt2) add (gen-if (gen-and gt1 lt3) sub (gen-if gt2 add (gen-ref 2)))))" +
                        WORDS +
                        "(define (run l k) (if (pair? (cdr l)) (run (cdr l) (body (vector (car l) (cadr l) k l 'nine))) k))" +
                        "(define (go n acc) (if (= n 0) acc (go (- n 1) (run words acc))))" +
                        EXIT_GO_ONCE + "(go 5000 0)";
        try (Run run = new Run("compiler.Inlining", "false")) {
            assertEquals(20000L, run.context.eval(SCHEME, program).asLong());
            String log = run.log();
            assertTrue(log, Pattern.compile("split\\s+\\d+-\\d+\\s+<lambda@").matcher(log).find());
            assertTrue(log, Pattern.compile("opt done\\s+engine=\\d+\\s+id=\\d+\\s+<lambda@\\S+ <split-\\d+>").matcher(log).find());
            assertEquals(log, "", invalidated(log));
        }
    }
}
