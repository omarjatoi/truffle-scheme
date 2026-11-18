package scheme.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.word.PointerBase;

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
 * <p>In a native image, the costs are not upper bounds: code that Truffle compiled at its first
 * tier inlines no call, so each level of a recursion through {@code apply} is a compiled frame and
 * the frames of a call between compiled call targets, 530 to 590 bytes where the costs count 420 to
 * 450 (1.3 times, more than the reserve covers on a stack of 64 MB). The image can tell how deep
 * its stack really is, though ({@link #stackPointer}), so there a call also spills when the stack
 * below it, from the root prompt, is deeper than the limit. Past half the limit ({@link #checks}),
 * a call checks each time it takes the stack use across a multiple of {@link #CHECK_INTERVAL}
 * ({@link #crosses}): an {@code xor} and compare on each such call, and a boundary call on one in
 * a hundred or more. Below half the limit, a call costs only the comparison with it, as on
 * HotSpot, and the costs would have to undercount by more than 2 times to overflow there. Checking
 * from the root prompt on, every 16 KB, made {@code fib} (40 calls deep) 25% slower, and checking
 * past a quarter, every 16 KB, made the first {@code ack(3,12)} 40% slower. Between two checks, the stack grows
 * by what 64 KB of costs stand for, which the reserve covers 4 times over. On HotSpot, Java code
 * can't see its stack's depth, and the costs, measured there, are what bounds it (design.md
 * "Stack-depth spill").
 *
 * <p>The limit: until a thread first needs more, {@link #FLOOR}, about the 50 interpreted calls
 * that M2-05 found fit in a 256 KB Java stack, the smallest the space tests run with. The first
 * time a call on a thread would pass it, the thread's remaining stack is measured
 * ({@link #remainingStack}), and the limit becomes most of it, less the reserve, never below the
 * floor. Each root prompt takes the limit of the thread it runs on.
 *
 * <p>The measurement touches the stack it measures (up to 64 MB), which stays resident, and takes
 * milliseconds to a few tenths of a second. An embedder that knows the stack size of the thread it
 * creates the context on (it started the thread with that size) gives it as
 * {@code scheme.StackSize}; on that thread, the limit comes from the size ({@link #limitFor}), and
 * nothing is measured. Any other thread that enters the context is measured as before. The
 * {@code scheme} launcher gives its evaluation thread's size (design.md D17).
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
    /**
     * What a thread's stack holds besides the calls the limit counts, when only its size is known:
     * the VM's guard zones and the thread's first frames (118 KB on HotSpot, macOS aarch64), and the
     * frames below the root prompt (the embedder's, the polyglot API's).
     */
    static final long THREAD_OVERHEAD = 256 * 1024;

    /**
     * Whether calls also check how deep the stack really is ({@link #pastLimit}): in a native image,
     * where it can be read. A constant, so that on HotSpot the checks fold away.
     */
    static final boolean READS_STACK = ImageInfo.inImageCode();
    /** In a native image, a call that takes the stack use across a multiple of this checks the stack's real depth. */
    static final int CHECK_INTERVAL = 64 * 1024;

    /** The measured limit of each thread, once a call on it has passed the floor. */
    private static final ThreadLocal<Integer> MEASURED = new ThreadLocal<>();

    private StackBudget() {
    }

    /**
     * Sets up a new context's costs: the option's fixed count of nested calls, or bytes. Called on
     * the thread the context is created on, whose stack is {@code stackSize} bytes if that isn't 0
     * ({@code scheme.StackSize}).
     */
    static void initialize(SchemeContext context, int spillDepth, long stackSize) {
        context.sizedThread = stackSize > 0 ? Thread.currentThread() : null;
        context.sizedThreadLimit = limitFor(stackSize);
        if (stackSize > 0 && spillDepth == 0 && SchemeLogs.on(SchemeLogs.SPILL)) {
            SchemeLogs.SPILL.fine("the stack of thread " + Thread.currentThread().getName() + " is " + stackSize + " bytes (scheme.StackSize), so recursion on it spills past " +
                            context.sizedThreadLimit + " bytes");
        }
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
        if (context.stackUse == 0) {
            if (READS_STACK) {
                context.stackBase = stackPointer();
            }
            if (!context.spillDepthFixed) {
                setThreadLimit(context);
            }
        }
    }

    /**
     * Whether a call to stack use {@code nested} may have to spill: it passes the limit, or, in a
     * native image, half of it, past which calls check the stack's real depth ({@link #crosses}).
     * Rarely: compiled code takes it as a slow path, and on HotSpot it is the comparison with the
     * limit alone.
     */
    static boolean checks(SchemeContext context, int nested) {
        int from = READS_STACK ? context.stackLimit >> 1 : context.stackLimit;
        return CompilerDirectives.injectBranchProbability(CompilerDirectives.SLOWPATH_PROBABILITY, nested > from);
    }

    /**
     * Whether a call from stack use {@code use} to {@code nested} checks the stack's real depth
     * ({@link #pastLimit}): in a native image, when it takes the stack use across a multiple of
     * {@link #CHECK_INTERVAL}.
     */
    static boolean crosses(int use, int nested) {
        return READS_STACK && (use ^ nested) >= CHECK_INTERVAL;
    }

    /**
     * Whether the stack below the caller, from the root prompt, is deeper than the limit (a native
     * image; never on HotSpot, where it can't be told). If the thread's stack hasn't been measured
     * yet, measures it first, as {@link #extend} would.
     */
    static boolean pastLimit(SchemeContext context) {
        return READS_STACK && !context.spillDepthFixed && deeperThanLimit(context);
    }

    @TruffleBoundary
    private static boolean deeperThanLimit(SchemeContext context) {
        long base = context.stackBase;
        long here = stackPointer();
        if (base == 0 || here == 0 || base - here <= context.stackLimit) {
            return false;
        }
        if (!context.stackMeasured) {
            measure(context, 0);
        }
        return base - here > context.stackLimit;
    }

    /**
     * About where the stack is (an address in the frame of this method, so the caller's stack pointer
     * less a few words), in a native image at run time; 0 elsewhere. The stack grows down, so the
     * stack between two calls of this is the difference of what they return.
     */
    @TruffleBoundary
    static long stackPointer() {
        if (!ImageInfo.inImageRuntimeCode()) {
            return 0;
        }
        PointerBase here = StackValue.get(Long.BYTES);
        return here.rawValue();
    }

    @TruffleBoundary
    private static void setThreadLimit(SchemeContext context) {
        if (Thread.currentThread() == context.sizedThread) {
            context.stackLimit = context.sizedThreadLimit;
            context.stackMeasured = true;
            return;
        }
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
        if (Thread.currentThread() == context.sizedThread) {
            context.stackLimit = context.sizedThreadLimit;
            context.stackMeasured = true;
            return use <= context.stackLimit;
        }
        Integer measured = MEASURED.get();
        if (measured == null) {
            context.stackProbes++;
            long remaining = remainingStack();
            // The stack use counts from the root prompt, and the stack remaining from here: this
            // ignores what the calls below already use (at most the floor), as margin.
            long limit = remaining - Math.max(RESERVE, remaining / 8);
            measured = (int) Math.max(FLOOR, Math.min(limit, MAX_LIMIT));
            MEASURED.set(measured);
            if (SchemeLogs.on(SchemeLogs.SPILL)) {
                SchemeLogs.SPILL.fine("measured the stack of thread " + Thread.currentThread().getName() + ": " + remaining + " bytes left, so recursion spills past " + measured + " bytes");
            }
        }
        context.stackLimit = measured;
        context.stackMeasured = true;
        return use <= measured;
    }

    /**
     * The limit on a thread whose stack is {@code stackSize} bytes, the whole of it from its start:
     * what a measurement of it would give. The stack less {@link #THREAD_OVERHEAD} is what remains,
     * of which at most {@link #MEASURED_MAX} counts, as a measurement looks no further; the limit is
     * that less the reserve, never below the floor. So the size only spares the measurement, and a
     * large stack doesn't raise the limit (design.md "Stack-depth spill").
     */
    static int limitFor(long stackSize) {
        long remaining = Math.min(stackSize - THREAD_OVERHEAD, MEASURED_MAX);
        long limit = remaining - Math.max(RESERVE, remaining / 8);
        return (int) Math.max(FLOOR, Math.min(limit, MAX_LIMIT));
    }

    /**
     * A {@code scheme.StackSize}: bytes, or with a suffix {@code k}, {@code m} or {@code g} (as
     * {@code -Xss} takes it); 0 for unknown.
     */
    static Long parseStackSize(String value) {
        String digits = value;
        int shift = 0;
        if (!value.isEmpty()) {
            switch (Character.toLowerCase(value.charAt(value.length() - 1))) {
                case 'k' -> shift = 10;
                case 'm' -> shift = 20;
                case 'g' -> shift = 30;
                default -> shift = 0;
            }
            if (shift != 0) {
                digits = value.substring(0, value.length() - 1);
            }
        }
        if (digits.isEmpty() || digits.length() > 15 || !digits.chars().allMatch(c -> c >= '0' && c <= '9') || Long.parseLong(digits) > Long.MAX_VALUE >> shift) {
            throw new IllegalArgumentException("scheme.StackSize needs a number of bytes, with k, m or g after it or not (such as 256m): " + value);
        }
        return Long.parseLong(digits) << shift;
    }

    // ------------------------------------------------------------------------------------------
    // Measuring the stack

    /**
     * The stack sizes of the two reference threads: their difference calibrates a probe frame, and
     * the larger one, less {@link #THREAD_OVERHEAD}, bounds it.
     */
    private static final long SMALL_STACK = 512 * 1024;
    private static final long LARGE_STACK = 2048 * 1024;
    /**
     * At most this much of a thread's stack is measured, and so touched (a stack's pages, once
     * touched, stay in memory): room for some 200,000 compiled calls. A given size counts as no more
     * either ({@link #limitFor}).
     */
    static final long MEASURED_MAX = 64L * 1024 * 1024;
    private static final int ROUNDS = 12;

    /**
     * About how many bytes of Java stack remain below the caller (at most {@link #MEASURED_MAX}), or
     * 0 if that can't be told. Java has no way to ask, so: a probe recurses until the stack
     * overflows, here and on two reference threads with stacks of known sizes, which give the bytes
     * per probe frame ({@link #bytesPerFrame}). A JIT compiles the probe while it runs, and its
     * compiled frames are much smaller (and may hold several levels), so a round probes the
     * references, here twice, and the references again, and counts only if the two figures for a
     * frame agree, and so do the two depths here; the result is the smaller of two rounds in a row
     * that agree. Once per thread, the first time a call on it passes the floor: a few milliseconds.
     *
     * <p>If no two rounds agree, the result is the smallest any round gave, each with the smaller of
     * its two figures for a frame. The JIT makes the probe's frames smaller as it goes, so that
     * figure is no larger than the frames here, measured between them, and the result
     * underestimates what remains: never the floor because a busy machine kept the JIT going.
     */
    @TruffleBoundary
    static long remainingStack() {
        long previous = -1;
        long smallest = Long.MAX_VALUE;
        try {
            for (int round = 0; round < ROUNDS; round++) {
                double bytesPerFrame = bytesPerFrame();
                if (bytesPerFrame <= 0) {
                    previous = -1;
                    continue;
                }
                long cap = (long) (MEASURED_MAX / bytesPerFrame);
                long here = new Probe(cap).depth();
                long hereAgain = new Probe(cap).depth();
                double bytesPerFrameAgain = bytesPerFrame();
                if (bytesPerFrameAgain <= 0) {
                    previous = -1;
                    continue;
                }
                long bytes = (long) (Math.min(here, hereAgain) * Math.min(bytesPerFrame, bytesPerFrameAgain));
                smallest = Math.min(smallest, bytes);
                if (!agree(bytesPerFrame, bytesPerFrameAgain) || !agree(here, hereAgain)) {
                    previous = -1;
                    continue;
                }
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
        return smallest == Long.MAX_VALUE ? 0 : smallest;
    }

    /**
     * The bytes per probe frame the reference threads give, or 0 if they don't. The difference in
     * their sizes over the difference in their depths is a frame's size whatever the thread's own
     * overhead, if each thread's stack is the size it asked for. It may be larger: on Linux, glibc
     * gives a new thread the cached stack of an exited one up to 4 times the size it asks for, if
     * none of that size is free (once one 512 KB thread ran on a 1 MB stack, every later one did,
     * and as deep as the 1 MB thread, so that no round counted, on CI's executors). That makes the
     * difference too large if it is the small thread's, so the figure is at most the large
     * thread's size less {@link #THREAD_OVERHEAD} over its depth, which a larger stack only makes
     * smaller.
     */
    private static double bytesPerFrame() throws InterruptedException {
        long small = onThread(SMALL_STACK);
        long large = onThread(LARGE_STACK);
        if (small <= 0 || large <= 0) {
            return 0;
        }
        double bound = (double) (LARGE_STACK - THREAD_OVERHEAD) / large;
        return large > small ? Math.min((double) (LARGE_STACK - SMALL_STACK) / (large - small), bound) : bound;
    }

    /** Within 2% of each other. */
    private static boolean agree(double a, double b) {
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

        /**
         * The deepest frame that can catch the overflow does, and the rest return normally: an
         * error unwinding through millions of compiled frames took 0.3 s on a 16 MB stack, four
         * times longer.
         */
        private void down() {
            if (++depth < cap) {
                try {
                    down();
                } catch (StackOverflowError e) {
                    // The bottom.
                }
            }
        }
    }
}
