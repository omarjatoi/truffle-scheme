package scheme.test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import org.graalvm.polyglot.Context;
import org.junit.Test;

/**
 * M8-01: the stack-depth spill (design.md "Continuations") happens when the Java stack the nested
 * calls use approaches what the thread has, not after a fixed number of calls. A recursion that fits
 * in the thread's stack stays on it; on a small stack, deep recursion still spills and never
 * overflows; the limit belongs to the thread that runs the code; and the {@code scheme.SpillDepth}
 * option fixes the old count of nested calls instead.
 */
public class StackBudgetTest {

    private static final String SCHEME = "scheme";

    private static final String COUNT = "(define (count i n) (if (= i n) 0 (+ 1 (count (+ i 1) n))))";

    /** Runs {@code body} on a new thread with a Java stack of {@code stackSize} bytes. */
    private static <T> T onThread(long stackSize, Supplier<T> body) throws InterruptedException {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(null, () -> {
            try {
                result.set(body.get());
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "stack-budget-test", stackSize);
        thread.start();
        thread.join();
        if (failure.get() instanceof Error e) {
            throw e;
        } else if (failure.get() instanceof RuntimeException e) {
            throw e;
        }
        return result.get();
    }

    /** How many spills {@code program} makes in {@code context}, and checks it returns {@code expected}. */
    private static long spills(Context context, String program, long expected) {
        long before = context.eval(SCHEME, "(%spills)").asLong();
        assertEquals(expected, context.eval(SCHEME, program).asLong());
        return context.eval(SCHEME, "(%spills)").asLong() - before;
    }

    private static <T> T withContext(Function<Context, T> body) {
        try (Context context = Context.create(SCHEME)) {
            return body.apply(context);
        }
    }

    @Test
    public void recursionThatFitsInTheStackDoesNotSpill() throws InterruptedException {
        // 64 MB of stack: 3000 nested calls fit whether they are interpreted or compiled, so none
        // spills. (It used to spill every 50 calls, whatever the stack.)
        long spills = onThread(64L << 20, () -> withContext(context -> {
            context.eval(SCHEME, COUNT);
            long total = 0;
            for (int i = 0; i < 20; i++) {
                total += spills(context, "(count 0 3000)", 3000);
            }
            return total;
        }));
        assertEquals(0L, spills);
    }

    @Test
    public void deepRecursionOnASmallStackSpills() throws InterruptedException {
        // 256 KB of stack, the smallest the space tests use: a million nested calls spill, and
        // never overflow the stack.
        long spills = onThread(256L << 10, () -> withContext(context -> {
            context.eval(SCHEME, COUNT);
            return spills(context, "(count 0 1000000)", 1_000_000);
        }));
        assertTrue("spills: " + spills, spills > 1000);
    }

    @Test
    public void theLimitIsTheRunningThreads() throws InterruptedException {
        // One context, used from a thread with a large stack (which measures it and raises the
        // limit) and then from one with a small stack: the second must not inherit the first's limit.
        try (Context context = Context.create(SCHEME)) {
            context.eval(SCHEME, COUNT);
            long big = onThread(64L << 20, () -> spills(context, "(count 0 3000)", 3000));
            assertEquals(0L, big);
            long small = onThread(256L << 10, () -> spills(context, "(count 0 100000)", 100_000));
            assertTrue("spills: " + small, small > 100);
        }
    }

    /** How many times {@code context} has measured a thread's stack. */
    private static long probes(Context context) {
        return context.eval(SCHEME, "(%stack-probes)").asLong();
    }

    @Test
    public void aThreadMeasuresItsStackOnce() throws InterruptedException {
        // Without scheme.StackSize, the first call past the floor on a thread measures its stack.
        long[] result = onThread(64L << 20, () -> withContext(context -> {
            context.eval(SCHEME, COUNT);
            long before = probes(context);
            long spills = spills(context, "(count 0 3000)", 3000) + spills(context, "(count 0 3000)", 3000);
            return new long[]{before, probes(context), spills};
        }));
        assertArrayEquals(new long[]{0, 1, 0}, result);
    }

    @Test
    public void stackSizeOptionGivesTheCreatingThreadsLimitWithoutAProbe() throws InterruptedException {
        // scheme.StackSize is the stack size of the thread the context is created on: there, the
        // limit comes from it and nothing measures the stack. 20,000 nested calls fit in 64 MB.
        try (Context context = onThread(64L << 20, () -> {
            Context created = Context.newBuilder(SCHEME).option("scheme.StackSize", "64m").build();
            created.eval(SCHEME, COUNT);
            assertEquals(0L, spills(created, "(count 0 20000)", 20_000));
            assertEquals(0L, probes(created));
            return created;
        })) {
            // Another thread that enters the context measures its own stack, as before.
            long small = onThread(256L << 10, () -> spills(context, "(count 0 100000)", 100_000));
            assertTrue("spills: " + small, small > 100);
            assertEquals(1L, probes(context));
        }
    }

    @Test
    public void stackSizeOptionOfASmallStackSpills() throws InterruptedException {
        // Given a 256 KB stack's size, a million nested calls spill, as with a measured one, and
        // never overflow the stack.
        long[] result = onThread(256L << 10, () -> {
            try (Context context = Context.newBuilder(SCHEME).option("scheme.StackSize", "262144").build()) {
                context.eval(SCHEME, COUNT);
                return new long[]{spills(context, "(count 0 1000000)", 1_000_000), probes(context)};
            }
        });
        assertTrue("spills: " + result[0], result[0] > 1000);
        assertEquals(0L, result[1]);
    }

    @Test
    public void stackSizeOptionNeedsASize() {
        for (String bad : new String[]{"lots", "-1m", "12q", ""}) {
            try (Context context = Context.newBuilder(SCHEME).option("scheme.StackSize", bad).build()) {
                fail("scheme.StackSize=" + bad + " was accepted: " + context);
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("scheme.StackSize"));
            }
        }
    }

    @Test
    public void spillDepthOptionFixesTheNumberOfNestedCalls() throws InterruptedException {
        // With scheme.SpillDepth=50, a call nested 50 deep spills, as before M8-01, however much
        // stack there is: 1000 nested calls spill about every 50.
        long spills = onThread(64L << 20, () -> {
            try (Context context = Context.newBuilder(SCHEME).allowExperimentalOptions(true).option("scheme.SpillDepth", "50").build()) {
                context.eval(SCHEME, COUNT);
                return spills(context, "(count 0 1000)", 1000);
            }
        });
        assertTrue("spills: " + spills, spills >= 15 && spills <= 25);
    }
}
