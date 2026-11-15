package scheme.spike;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import java.util.Arrays;
import java.util.Locale;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.Test;

/**
 * M0-03: the cost of call, then IsCapture, then SuspendFrame at every call site, on the GraalVM JIT
 * with normal (background) compilation. Skipped unless run via {@code mx r7rs-spike --bench}.
 */
public class CallSiteCostBench {

    private static final int WARMUP = Integer.getInteger("scheme.spike.bench.warmup", 150);
    private static final int MEASURE = Integer.getInteger("scheme.spike.bench.measure", 60);
    private static final int ROUNDS = Integer.getInteger("scheme.spike.bench.rounds", 3);

    private static double[] measure(String program, long expected) {
        try (Context context = Context.create(SpikeLanguage.ID)) {
            Value benchmark = context.parse(SpikeLanguage.ID, program);
            for (int i = 0; i < WARMUP; i++) {
                assertEquals(expected, benchmark.execute().asLong());
            }
            double[] millis = new double[MEASURE];
            for (int i = 0; i < MEASURE; i++) {
                long start = System.nanoTime();
                long result = benchmark.execute().asLong();
                millis[i] = (System.nanoTime() - start) / 1e6;
                assertEquals(expected, result);
            }
            Arrays.sort(millis);
            return millis;
        }
    }

    private static void compare(String name, String args, long expected) {
        for (int round = 1; round <= ROUNDS; round++) {
            double[] plain = measure("bench " + name + " plain " + args, expected);
            double[] protocol = measure("bench " + name + " protocol " + args, expected);
            double plainMedian = plain[plain.length / 2];
            double protocolMedian = protocol[protocol.length / 2];
            System.out.println(String.format(Locale.ROOT,
                            "BENCH %s(%s) round %d: plain median %.3f ms (min %.3f), protocol median %.3f ms (min %.3f), overhead %+.1f%%",
                            name, args, round, plainMedian, plain[0], protocolMedian, protocol[0],
                            (protocolMedian / plainMedian - 1) * 100));
        }
    }

    @Test
    public void fib30() {
        assumeTrue(Boolean.getBoolean("scheme.spike.bench"));
        compare("fib", "30", 832040L);
    }

    @Test
    public void tak() {
        assumeTrue(Boolean.getBoolean("scheme.spike.bench"));
        compare("tak", "24 16 8", 9L);
    }
}
