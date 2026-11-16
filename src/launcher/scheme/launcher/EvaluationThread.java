package scheme.launcher;

import java.lang.management.ManagementFactory;
import java.util.Locale;

/**
 * The thread the {@code scheme} launcher evaluates on (design.md D17): one with a large Java stack,
 * unless a stack size is given.
 *
 * <p>Deep non-tail recursion stays on the Java stack until it has used most of the thread's stack,
 * and spills to the heap after that (design.md "Stack-depth spill", plan.md M8-01), which costs
 * about 1 KB and 240 ns per frame. A JVM thread's stack is 1 or 2 MB by default, a few thousand
 * compiled calls; on a large stack, a recursion tens of thousands deep stays on it
 * ({@code ack(3,12)}, 32,765 deep: 24.6 s on 2 MB, 2.4 s on 256 MB). The stack's address space is
 * reserved when the thread starts; its pages are touched only as a recursion reaches them.
 *
 * <p>A stack size given to the VM ({@code -Xss}) is the launcher's own thread's, so the launcher
 * evaluates on that thread, as it did before it had a thread of its own: the spec and space tests
 * run it with {@code -Xss256k}, so that their deep recursions spill (AGENTS.md rule 4).
 * {@code --stack-size=<size>} (or the native launcher's {@code --vm.Xss<size>}) gives the
 * evaluation thread that size, and {@code --stack-size=default} keeps the launcher's own thread
 * (on the JVM, its default thread stack; in the native launcher, the process's main thread).
 */
final class EvaluationThread {

    /**
     * The evaluation thread's stack when no size is given. The spill limit uses at most the first
     * 64 MB of a thread's stack (StackBudget measures no further), so that is all a recursion
     * touches; the rest is address space. A stack under about 72 MB is slower to measure (the
     * measurement overflows it: 0.3 to 0.6 s on 16 or 64 MB), and 256 MB and 1 GB measured alike.
     */
    static final long LARGE_STACK = 256L << 20;
    /** As a stack size: no thread of its own, the launcher's thread. */
    static final long LAUNCHER_THREAD = 0;
    static final String DEFAULT = "default";

    private EvaluationThread() {
    }

    /**
     * A stack size: bytes, or with a suffix {@code k}, {@code m} or {@code g} (as {@code -Xss}
     * takes it), or {@link #DEFAULT} for {@link #LAUNCHER_THREAD}. Null if it is neither.
     */
    static Long parseSize(String value) {
        if (value.equals(DEFAULT)) {
            return LAUNCHER_THREAD;
        }
        String digits = value;
        int shift = 0;
        if (!value.isEmpty()) {
            switch (value.toLowerCase(Locale.ROOT).charAt(value.length() - 1)) {
                case 'k' -> shift = 10;
                case 'm' -> shift = 20;
                case 'g' -> shift = 30;
                default -> shift = 0;
            }
            if (shift != 0) {
                digits = value.substring(0, value.length() - 1);
            }
        }
        if (digits.isEmpty() || !digits.chars().allMatch(c -> c >= '0' && c <= '9') || digits.length() > 12) {
            return null;
        }
        long size = Long.parseLong(digits) << shift;
        return size > 0 ? size : null;
    }

    /**
     * Whether the VM was given a stack size: HotSpot's {@code -Xss} or {@code -XX:ThreadStackSize}
     * (also through {@code JDK_JAVA_OPTIONS} or {@code JAVA_TOOL_OPTIONS}), or the native
     * executable's {@code -Xss} or {@code -XX:StackSize}, which it takes from its command line
     * before the launcher sees it. (The native launcher's {@code --vm.Xss} is the launcher's.)
     */
    static boolean givenToTheVM() {
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (arg.startsWith("-Xss") || arg.startsWith("-XX:ThreadStackSize=") || arg.startsWith("-XX:StackSize=")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs {@code body} on a new thread with a stack of {@code stackSize} bytes, and waits for it;
     * what it throws, this throws. On this thread if the size is {@link #LAUNCHER_THREAD}, or if
     * the system won't reserve the stack (a limit on address space).
     */
    static void run(long stackSize, Runnable body) {
        if (stackSize == LAUNCHER_THREAD) {
            body.run();
            return;
        }
        Throwable[] failure = new Throwable[1];
        Thread thread = new Thread(null, () -> {
            try {
                body.run();
            } catch (Throwable t) {
                failure[0] = t;
            }
        }, "scheme-main", stackSize);
        try {
            thread.start();
        } catch (OutOfMemoryError e) {
            body.run();
            return;
        }
        boolean joined = false;
        while (!joined) {
            try {
                thread.join();
                joined = true;
            } catch (InterruptedException e) {
                // Nothing interrupts the launcher's thread; wait on.
            }
        }
        if (failure[0] instanceof RuntimeException e) {
            throw e;
        } else if (failure[0] instanceof Error e) {
            throw e;
        } else if (failure[0] != null) {
            throw new IllegalStateException(failure[0]);
        }
    }
}
