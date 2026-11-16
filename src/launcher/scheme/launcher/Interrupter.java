package scheme.launcher;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;

/**
 * Ctrl-C in the REPL while an input is evaluated (plan.md M8-06, design.md D17): it interrupts the
 * evaluation, which stops at its next Truffle safepoint, and the REPL goes on with the next input.
 * The interrupt runs on a thread of its own ({@link Context#interrupt} waits for the evaluation to
 * stop, and Ctrl-C's handler mustn't wait), and the REPL waits for it to finish ({@link #end})
 * before it reads again, so it never reaches the REPL's own reads.
 *
 * <p>An evaluation that doesn't stop (one waiting in host code that never polls a safepoint) would
 * keep the REPL from ever reading again, and the session from ending: so Ctrl-C while an interrupt
 * has been waiting for at least {@code patience} gives up, and ends the process ({@code giveUp}).
 * Sooner than that, it does nothing more: pressing Ctrl-C twice doesn't end the session.
 */
public final class Interrupter {

    /** The status a session given up on exits with: 128 + SIGINT, as SIGINT's default action. */
    public static final int EXIT_INTERRUPTED = 130;
    /** How long an interrupt waits before Ctrl-C gives up on it. */
    public static final Duration PATIENCE = Duration.ofSeconds(1);

    private final Runnable interrupt;
    private final Runnable giveUp;
    private final long patienceNanos;
    /** Whether an input is being evaluated: Ctrl-C does nothing otherwise. */
    private boolean evaluating;
    /** The interrupt of this evaluation, when there is one, and when it started. */
    private Thread interrupting;
    private long interruptStart;

    /**
     * {@code interrupt} interrupts the evaluation and returns once it has stopped; {@code giveUp}
     * ends the process.
     */
    public Interrupter(Runnable interrupt, Runnable giveUp, Duration patience) {
        this.interrupt = interrupt;
        this.giveUp = giveUp;
        this.patienceNanos = patience.toNanos();
    }

    /** Interrupts {@code context}'s evaluations. */
    public static Interrupter of(Context context, Runnable giveUp) {
        return new Interrupter(() -> interrupt(context), giveUp, PATIENCE);
    }

    private static void interrupt(Context context) {
        try {
            // Zero: until the evaluation has stopped.
            context.interrupt(Duration.ZERO);
        } catch (TimeoutException e) {
            throw new AssertionError("an interrupt without a timeout timed out", e);
        } catch (PolyglotException | IllegalStateException e) {
            // The context is closed, or closing: there is nothing left to interrupt.
        }
    }

    /** An evaluation starts. */
    public synchronized void begin() {
        evaluating = true;
    }

    /** The evaluation is over: waits for its interrupt, if it has one, to finish. */
    public void end() {
        Thread thread;
        synchronized (this) {
            evaluating = false;
            thread = interrupting;
            interrupting = null;
        }
        if (thread != null) {
            // The evaluation has left the context, so the interrupt finishes.
            boolean joined = false;
            while (!joined) {
                try {
                    thread.join();
                    joined = true;
                } catch (InterruptedException e) {
                    // Wait on: the REPL reads next, and a pending interrupt would stop that.
                }
            }
        }
    }

    /**
     * Ctrl-C on the evaluation's own thread, in host code that goes back to the evaluation next
     * (JLine reading a line for Scheme code): waits, for at most {@link #PATIENCE}, until the
     * interrupt has been submitted, which is when it starts to wait for the evaluation to stop.
     * Otherwise the evaluation could go on from the read and end before the interrupt reaches it.
     */
    public void requestFromEvaluation() {
        request();
        Thread thread;
        synchronized (this) {
            thread = interrupting;
        }
        if (thread == null) {
            return;
        }
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING || state == Thread.State.TERMINATED) {
                return;
            }
            LockSupport.parkNanos(100_000);
        }
    }

    /** Whether Ctrl-C has asked to interrupt the evaluation. */
    public synchronized boolean pending() {
        return evaluating && interrupting != null;
    }

    /** Ctrl-C. */
    public synchronized void request() {
        if (!evaluating) {
            return;
        }
        if (interrupting == null) {
            interruptStart = System.nanoTime();
            interrupting = new Thread(interrupt, "scheme-interrupt");
            interrupting.setDaemon(true);
            interrupting.start();
        } else if (interrupting.isAlive() && System.nanoTime() - interruptStart >= patienceNanos) {
            giveUp.run();
        }
    }
}
