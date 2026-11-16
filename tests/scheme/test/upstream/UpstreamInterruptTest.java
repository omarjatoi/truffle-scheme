package scheme.test.upstream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.graalvm.polyglot.Context;
import org.junit.Test;

import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.debug.DebuggerSession;
import com.oracle.truffle.api.debug.SuspendedEvent;

/**
 * Truffle 25.5 bug: {@code Context.interrupt} that lands in a Bytecode DSL root at the safepoint
 * Truffle polls when the root returns throws an {@code AssertionError} instead of the interrupt,
 * when assertions are on (docs/upstream.md, "Interrupting a Bytecode DSL root as it returns").
 * Our workaround: InterruptTest runs with assertions off in the generated Scheme root.
 *
 * <p>Inverted, like every test in this package: it passes while the bug is there. When a Truffle
 * upgrade fixes the bug, it fails; then remove the workaround, this test and its entry in
 * docs/upstream.md.
 */
public class UpstreamInterruptTest {

    private static final long TIMEOUT_SECONDS = 60;

    @Test
    public void anInterruptAsABytecodeRootReturnsFailsAnAssertion() throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch suspended = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        try (Context context = Context.create(UpstreamLanguage.ID)) {
            Debugger debugger = Debugger.find(context.getEngine());
            try (DebuggerSession session = debugger.startSession((@SuppressWarnings("unused") SuspendedEvent event) -> {
                suspended.countDown();
                try {
                    resume.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            })) {
                session.suspendNextExecution();
                // The program: one statement, where it suspends, then a return.
                Thread evaluation = new Thread(() -> {
                    try {
                        context.eval(UpstreamLanguage.ID, "x");
                    } catch (Throwable t) {
                        thrown.set(t);
                    }
                });
                evaluation.start();
                assertTrue(suspended.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                // Interrupt while it is suspended: the interrupt waits for it to stop.
                Thread interrupt = new Thread(() -> {
                    try {
                        context.interrupt(Duration.ZERO);
                    } catch (TimeoutException e) {
                        throw new AssertionError(e);
                    }
                });
                interrupt.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
                while (interrupt.getState() != Thread.State.WAITING && interrupt.getState() != Thread.State.TIMED_WAITING) {
                    assertTrue("the interrupt didn't start waiting", System.nanoTime() < deadline);
                    Thread.sleep(1);
                }
                // Resumed, the root returns 42, and the interrupt lands at the poll after it
                // (OptimizedCallTarget.executeRootNode), with the root as its location.
                resume.countDown();
                evaluation.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
                interrupt.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            }
        }
        // Expected: a PolyglotException whose isInterrupted() is true. Actual, with -ea: the
        // generated root's findBytecodeIndex asserts its node isn't the root, while the
        // PolyglotException's stack trace is filled in.
        Throwable t = thrown.get();
        assertTrue(String.valueOf(t), t instanceof AssertionError);
        assertEquals("A BytecodeRootNode should not be used as a call location.", t.getMessage());
    }
}
