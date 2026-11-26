package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.Assume;
import org.junit.Test;

import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.dsl.Introspection;
import com.oracle.truffle.api.dsl.Introspection.SpecializationInfo;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;

import scheme.runtime.value.SClosure;

/**
 * M7-01: the steady state of a program doesn't depend on how far background compilation got. On
 * CI's 4-CPU runners Truffle has two compiler threads, and the benchmarks' compilations took longer
 * than the runs that were meant to measure compiled code, so each JVM measured a different mix of
 * compiled and not-yet-compiled code (plan.md M7-01). These tests keep the two causes of the excess
 * compilation from coming back:
 *
 * <ul>
 * <li>the spill path of a non-tail call site (the capture past the depth limit, design.md
 * "Continuations") is compiled only into the sites that have spilled; it was most of every site's
 * machine code;</li>
 * <li>a site performs the first tail call that comes back and the ones after it through two caches,
 * so a chain of tail calls ({@code map} to its loop to {@code reverse}) is compiled once per site,
 * not twice, and doesn't make the site look polymorphic, which split the procedure it is in for
 * every caller.</li>
 * </ul>
 */
public class StableWarmupTest {

    private static final String SCHEME = "scheme";

    /**
     * What the engine logs while it runs {@code program}, with the given engine options (and the
     * compiler, synchronously, once a root has been called 100 times). Skips the test when the
     * runtime has no compiler: then the options don't exist.
     */
    private static String engineLog(String program, String... options) throws IOException {
        Path log = Files.createTempFile("stable-warmup", ".log");
        Context.Builder builder = Context.newBuilder(SCHEME).allowExperimentalOptions(true).option("log.file", log.toString()).out(new ByteArrayOutputStream());
        builder.option("engine.Compilation", "true").option("engine.CompileImmediately", "false").option("engine.BackgroundCompilation", "false");
        builder.option("engine.MultiTier", "false").option("engine.SingleTierCompilationThreshold", "100");
        for (int i = 0; i < options.length; i += 2) {
            builder.option(options[i], options[i + 1]);
        }
        Context context;
        try {
            context = builder.build();
        } catch (IllegalArgumentException e) {
            Assume.assumeNoException("no optimizing Truffle runtime", e);
            throw e;
        }
        try (Context c = context) {
            c.eval(SCHEME, program);
        }
        try {
            return Files.readString(log);
        } finally {
            Files.delete(log);
        }
    }

    private static final Pattern DEOPT_F = Pattern.compile("opt deopt\\s+engine=\\d+\\s+id=\\d+\\s+f\\s");

    @Test
    public void onlySitesThatSpilledCompileTheSpill() throws IOException {
        Path log = Files.createTempFile("stable-warmup", ".log");
        // Spills past 50 nested calls, however much Java stack there is (plan.md M8-01).
        Context.Builder builder = Context.newBuilder(SCHEME).allowExperimentalOptions(true).option("scheme.SpillDepth", "50").option("log.file", log.toString()).out(new ByteArrayOutputStream());
        builder.option("engine.Compilation", "true").option("engine.CompileImmediately", "false").option("engine.BackgroundCompilation", "false");
        builder.option("engine.MultiTier", "false").option("engine.SingleTierCompilationThreshold", "100").option("engine.TraceCompilation", "true");
        Context context;
        try {
            context = builder.build();
        } catch (IllegalArgumentException e) {
            Assume.assumeNoException("no optimizing Truffle runtime", e);
            throw e;
        }
        try (Context c = context) {
            // f's call site sees a capture first (the call/cc at the bottom), so the path that
            // suspends its frame is part of its compiled code; then f is compiled, with no capture
            // or spill going on.
            c.eval(SCHEME, "(define saved #f) (define capture? #t)" +
                            "(define (f n) (if (= n 0) (if capture? (call/cc (lambda (k) (set! saved k) 0)) 0) (+ 1 (f (- n 1)))))" +
                            "(f 10) (set! capture? #f) (do ((k 0 (+ k 1))) ((= k 400)) (f 10))");
            String compiled = Files.readString(log);
            assertTrue(compiled, Pattern.compile("opt done\\s+engine=\\d+\\s+id=\\d+\\s+f\\s").matcher(compiled).find());
            assertFalse(compiled, DEOPT_F.matcher(compiled).find());
            // Now f recurses past the spill depth (50 nested calls, design.md "Continuations"). Its
            // compiled code has no spill path, so the first spill goes back to the interpreter. (It
            // used to have one at every site, about two thirds of each site's machine code.)
            assertEquals(200L, c.eval(SCHEME, "(f 200)").asLong());
            String spilled = Files.readString(log);
            assertTrue(spilled, DEOPT_F.matcher(spilled.substring(compiled.length())).find());
        } finally {
            Files.delete(log);
        }
    }

    @Test
    public void chainsOfTailCallsDoNotSplitTheCaller() throws IOException {
        // site calls walk, which tail-calls step1, which tail-calls step2 or step3: three procedures
        // come back to site's trampoline, step3 only from the second call on, but site always calls
        // the same procedure, so splitting site for each of its three callers gains nothing. (It
        // used to: the second call made site's one cache polymorphic.)
        String chain = "(define (step3 x) (+ x 1)) (define (step2 x) (+ x 2)) (define (step1 x) (if (even? x) (step2 x) (step3 x)))" +
                        "(define (walk x) (step1 x))" +
                        "(define (site x) (* 2 (walk x)))" +
                        "(define (a x) (+ 1 (site x))) (define (b x) (+ 2 (site x))) (define (c x) (+ 3 (site x)))";
        String log = engineLog(chain + "(let loop ((i 0) (s 0)) (if (= i 3000) s (loop (+ i 1) (+ s (a i) (b i) (c i)))))",
                        "engine.TraceSplitting", "true");
        assertFalse(log, Pattern.compile("split\\s+\\S+\\s+site\\b").matcher(log).find());
        // The first tail call (step1) has a cache of its own; the ones after it share the other.
        assertEquals(List.of(1, 2), tailCallCaches(chain + "(let loop ((i 0)) (if (< i 10) (begin (a i) (loop (+ i 1)))))", "site"));
    }

    @Test
    public void theTailCallsAfterTheFirstHaveALargerCache() {
        // M8-05: continuation-passing code (cpstak, graphs) returns every call of a continuation to
        // one trampoline, after its first tail call: here p1's tail call of p2 is the first, and p3
        // to p14, twelve procedures, come after it. Up to twelve are each called directly (and can
        // be inlined); past four (M8-05), and past eight (plan.md M12-05: graphs' sites have 9 to
        // 12), the site called them all indirectly, with Truffle's argument profiling on every call.
        StringBuilder chain = new StringBuilder();
        for (int i = 1; i < 14; i++) {
            chain.append("(define (p").append(i).append(" x) (p").append(i + 1).append(" x))");
        }
        chain.append("(define (p14 x) (+ x 1)) (define (site x) (* 2 (p1 x)))");
        assertEquals(List.of(1, 12), tailCallCaches(chain + "(let loop ((i 0)) (if (< i 10) (begin (site i) (loop (+ i 1)))))", "site"));
    }

    @Test
    public void onlyMegamorphicSitesSplitTheCaller() throws IOException {
        // The first procedure a callee tail-calls can depend on the caller: here run tail-calls the
        // procedure twice was given, so twice's site sees a different one for each caller of twice.
        // With up to four, the site's cache inlines each of them, and twice isn't split.
        Pattern split = Pattern.compile("split\\s+\\S+\\s+twice\\b");
        assertFalse(split.matcher(engineLog(passedDown(3), "engine.TraceSplitting", "true")).find());
        // With more, the site goes megamorphic, and splitting twice for each caller makes each
        // copy's site monomorphic again (plan.md M5-10).
        assertTrue(split.matcher(engineLog(passedDown(6), "engine.TraceSplitting", "true")).find());
    }

    /**
     * twice, called from {@code callers} procedures, each passing a lambda of its own that run
     * tail-calls. The calls are in a procedure the loop calls: Truffle 25.6 splits a callee when the
     * root that calls it is prepared for compilation, which the loop's root, run once, never is.
     */
    private static String passedDown(int callers) {
        StringBuilder program = new StringBuilder("(define (run f x) (f x)) (define (twice f x) (+ (run f x) (run f x)))");
        StringBuilder calls = new StringBuilder();
        for (int i = 0; i < callers; i++) {
            program.append("(define (c").append(i).append(" x) (twice (lambda (y) (+ y ").append(i).append(")) x))");
            calls.append(" (c").append(i).append(" i)");
        }
        return program + "(define (all i) (+" + calls + "))(let loop ((i 0) (s 0)) (if (= i 3000) s (loop (+ i 1) (+ s (all i)))))";
    }

    /**
     * How many procedures each tail-call cache of {@code name}'s call sites holds, after
     * {@code program}: the {@code doDirect} instances of each {@code TailCalls} node, in tree order.
     */
    private static List<Integer> tailCallCaches(String program, String name) {
        try (Context context = Context.create(SCHEME)) {
            ResultProbe probe = context.getEngine().getInstruments().get(ResultProbe.ID).lookup(ResultProbe.class);
            context.eval(SCHEME, program);
            context.eval(Source.newBuilder(SCHEME, name, ResultProbe.SOURCE).buildLiteral());
            BytecodeRootNode root = (BytecodeRootNode) ((SClosure) probe.last).callTarget.getRootNode();
            List<Integer> sizes = new ArrayList<>();
            for (Instruction instruction : root.getBytecodeNode().getInstructions()) {
                for (Instruction.Argument argument : instruction.getArguments()) {
                    if (argument.getKind() != Instruction.Argument.Kind.NODE_PROFILE || argument.asCachedNode() == null) {
                        continue;
                    }
                    for (Node node : NodeUtil.findAllNodeInstances(argument.asCachedNode(), Node.class)) {
                        if (node.getClass().getSimpleName().startsWith("TailCalls") && Introspection.isIntrospectable(node)) {
                            int direct = 0;
                            for (SpecializationInfo s : Introspection.getSpecializations(node)) {
                                if (s.getMethodName().equals("doDirect")) {
                                    direct = s.getInstances();
                                }
                            }
                            sizes.add(direct);
                        }
                    }
                }
            }
            return sizes;
        }
    }
}
