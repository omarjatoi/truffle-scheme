package scheme.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * How much Java stack nested non-tail calls may use before a call spills instead (design.md
 * "Stack-depth spill", plan.md M8-01).
 *
 * <p>{@link SchemeContext#stackUse} estimates, in bytes, the Java stack the non-tail calls nested
 * since the root prompt use: each call adds what its caller's frame costs while it waits
 * ({@link #callCost}), and takes it off when it returns. A call that would take it past
 * {@link SchemeContext#stackLimit} spills. The costs are upper bounds measured on HotSpot
 * (aarch64), with a margin:
 * <ul>
 * <li>A frame in the interpreter is the bytecode interpreter's Java frame, whatever the procedure:
 * 1.7 to 2.2 KB while HotSpot interprets the interpreter, 0.5 to 0.7 KB once it has compiled it.
 * {@link #INTERPRETED_CALL} is 2.5 KB.</li>
 * <li>A compiled frame keeps the procedure's frame slots (locals and operand stack) that are live
 * across the call: 165 bytes per call for a one-argument recursion, 870 for one with 16 values live
 * across the call. {@link #COMPILED_CALL} plus {@link #COMPILED_CALL_PER_SLOT} per slot of the
 * caller's frame.</li>
 * </ul>
 * Run to a stack overflow with no spill, recursions of those two kinds and one through
 * {@code guard} counted more than 1.2 times the stack they used, compiled, and more than 1.5 times
 * interpreted (M8-01).
 * In compiled code, a callee inlined into its caller shares its Java frame, so its calls cost less
 * than they count. A frame that deoptimizes becomes interpreter frames while it runs, which the
 * {@link #RESERVE} covers, with whatever runs at the deepest point (primitives, a debugger
 * stopped there, class loading).
 *
 * <p>The limit: until a thread first needs more, {@link #FLOOR}, about the 50 interpreted calls
 * that M2-05 found fit in a 256 KB Java stack, the smallest the space tests run with. The first
 * time a call on a thread would pass it, the thread's remaining stack is measured
 * ({@link #remainingStack}), and the limit becomes most of it, less the reserve, never below the
 * floor. Each root prompt takes the limit of the thread it runs on.
 *
 * <p>The {@code scheme.SpillDepth} option instead counts each nested call as 1, and spills past that
 * many, as before M8-01 (tests that need frames on the heap at a known depth).
 */
final class StackBudget {

    /** What a call made from the interpreter adds to the stack use, in bytes. */
    static final int INTERPRETED_CALL = 2560;
    /** What a call made from compiled code adds, besides {@link #COMPILED_CALL_PER_SLOT} per slot of the caller's frame. */
    static final int COMPILED_CALL = 128;
    static final int COMPILED_CALL_PER_SLOT = 32;
    /** The limit before the thread's stack is measured: 50 interpreted calls. */
    static final int FLOOR = 50 * INTERPRETED_CALL;
    /** Stack kept free below the deepest call: this much, or an eighth of the stack if that is more. */
    static final long RESERVE = 256 * 1024;
    /** The largest limit, so that the stack use never overflows an int. */
    static final int MAX_LIMIT = Integer.MAX_VALUE / 2;

    /** The measured limit of each thread, once a call on it has passed the floor. */
    private static final ThreadLocal<Integer> MEASURED = new ThreadLocal<>();

    private StackBudget() {
    }

    /** Sets up a new context's costs: the option's fixed count of nested calls, or bytes. */
    static void initialize(SchemeContext context, int spillDepth) {
        if (spillDepth > 0) {
            context.interpretedCallCost = 1;
            context.compiledCallCost = 1;
            context.compiledSlotCost = 0;
            context.stackLimit = spillDepth;
            context.stackMeasured = true;
            context.spillDepthFixed = true;
        } else {
            context.interpretedCallCost = INTERPRETED_CALL;
            context.compiledCallCost = COMPILED_CALL;
            context.compiledSlotCost = COMPILED_CALL_PER_SLOT;
            context.stackLimit = FLOOR;
            context.stackMeasured = false;
            context.spillDepthFixed = false;
        }
    }

    /** What a non-tail call adds to the stack use: the frame of the caller, with {@code slots} slots, while it waits. */
    static int callCost(SchemeContext context, int slots) {
        if (CompilerDirectives.inInterpreter()) {
            return context.interpretedCallCost;
        }
        return context.compiledCallCost + context.compiledSlotCost * slots;
    }

    /** What a prompt's Java frames add (a driver loop and the call of its thunk). */
    static int promptCost(SchemeContext context) {
        return context.interpretedCallCost;
    }

    /**
     * A root prompt starts on the current thread with no calls nested: it takes the thread's limit.
     * A root prompt nested in a call (an embedder's callback) keeps its caller's.
     */
    static void enterRoot(SchemeContext context) {
        if (context.stackUse == 0 && !context.spillDepthFixed) {
            setThreadLimit(context);
        }
    }

    @TruffleBoundary
    private static void setThreadLimit(SchemeContext context) {
        Integer measured = MEASURED.get();
        context.stackLimit = measured == null ? FLOOR : measured;
        context.stackMeasured = measured != null;
    }

    /**
     * A call would take the stack use to {@code use}, past the limit. If the thread's stack hasn't
     * been measured yet, measures it and raises the limit; returns whether the call fits now.
     */
    static boolean extend(SchemeContext context, int use) {
        if (context.stackMeasured) {
            return false;
        }
        return measure(context, use);
    }

    @TruffleBoundary
    private static boolean measure(SchemeContext context, int use) {
        Integer measured = MEASURED.get();
        if (measured == null) {
            long remaining = remainingStack();
            // The stack use counts from the root prompt, and the stack remaining from here: this
            // ignores what the calls below already use (at most the floor), as margin.
            long limit = remaining - Math.max(RESERVE, remaining / 8);
            measured = (int) Math.max(FLOOR, Math.min(limit, MAX_LIMIT));
            MEASURED.set(measured);
        }
        context.stackLimit = measured;
        context.stackMeasured = true;
        return use <= measured;
    }

    // ------------------------------------------------------------------------------------------
    // Measuring the stack

    /** The stack sizes of the two reference threads: their difference calibrates a probe frame. */
    private static final long SMALL_STACK = 512 * 1024;
    private static final long LARGE_STACK = 1024 * 1024;
    /**
     * At most this much of a thread's stack is measured, and so touched (a stack's pages, once
     * touched, stay in memory): room for some 200,000 compiled calls.
     */
    private static final long MEASURED_MAX = 64L * 1024 * 1024;
    private static final int ROUNDS = 12;

    /**
     * About how many bytes of Java stack remain below the caller (at most {@link #MEASURED_MAX}), or
     * 0 if that can't be told. Java has no way to ask, so: a probe recurses until the stack
     * overflows, here and on two threads with stacks of known sizes. The reference threads give the
     * bytes per probe frame (the difference in their sizes over the difference in their depths),
     * whatever a frame's size, as long as every probe runs the same code. A JIT compiles the probe
     * while it runs, and its compiled frames are much smaller (and may hold several levels), so a
     * round probes the references, here twice, and the references again, and counts only if each
     * pair agrees; the result is the smaller of two rounds in a row that agree. Once per thread, the
     * first time a call on it passes the floor: a few milliseconds.
     */
    @TruffleBoundary
    static long remainingStack() {
        long previous = -1;
        try {
            for (int round = 0; round < ROUNDS; round++) {
                long small = onThread(SMALL_STACK);
                long large = onThread(LARGE_STACK);
                if (small <= 0 || large <= small) {
                    previous = -1;
                    continue;
                }
                double bytesPerFrame = (double) (LARGE_STACK - SMALL_STACK) / (large - small);
                long cap = (long) (MEASURED_MAX / bytesPerFrame);
                long here = new Probe(cap).depth();
                long hereAgain = new Probe(cap).depth();
                long smallAgain = onThread(SMALL_STACK);
                long largeAgain = onThread(LARGE_STACK);
                if (!agree(small, smallAgain) || !agree(large, largeAgain) || !agree(here, hereAgain)) {
                    previous = -1;
                    continue;
                }
                long bytes = (long) (Math.min(here, hereAgain) * bytesPerFrame);
                if (previous >= 0 && agree(previous, bytes)) {
                    return Math.min(previous, bytes);
                }
                previous = bytes;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | OutOfMemoryError e) {
            // No thread could be started (an embedder's limit): the floor it is.
        }
        return 0;
    }

    /** Within 2% of each other. */
    private static boolean agree(long a, long b) {
        return Math.abs(a - b) * 50 <= Math.max(a, b);
    }

    private static long onThread(long stackSize) throws InterruptedException {
        Probe probe = new Probe(Long.MAX_VALUE);
        long[] result = new long[1];
        Thread thread = new Thread(null, () -> result[0] = probe.depth(), "scheme-stack-probe", stackSize);
        thread.setDaemon(true);
        thread.start();
        thread.join();
        return result[0];
    }

    /** Recurses until the Java stack overflows, or {@code cap} deep: how deep it got. */
    private static final class Probe {
        private final long cap;
        private long depth;

        Probe(long cap) {
            this.cap = cap;
        }

        long depth() {
            try {
                down();
            } catch (StackOverflowError e) {
                // The bottom.
            }
            return depth;
        }

        private void down() {
            if (++depth < cap) {
                down();
            }
        }
    }
}
