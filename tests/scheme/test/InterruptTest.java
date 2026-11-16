package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.Test;

import scheme.launcher.Interrupter;
import scheme.launcher.SchemeMain;

/**
 * M8-06: Ctrl-C in the REPL interrupts the evaluation ({@link Interrupter}, design.md D17). Here in
 * this process, without a terminal: computations that never end stop at a Truffle safepoint, the
 * context stays usable, and the dynamic environment is back as it was. The pty test
 * ({@code //tests:tools_terminal}) sends a real Ctrl-C.
 */
public class InterruptTest {

    static {
        // Truffle 25.5 fails an assertion in the generated root when an interrupt lands at the
        // safepoint after a root returns (docs/upstream.md, "Interrupting a Bytecode DSL root as it
        // returns"; UpstreamInterruptTest). Without assertions, as the launcher runs, the
        // interrupt is right. Before the class is loaded: this test's JVM runs only this class.
        SchemeMain.class.getClassLoader().setClassAssertionStatus("scheme.runtime.SchemeRootNodeGen", false);
    }

    private static final long TIMEOUT_SECONDS = 120;

    /** Never-ending computations, each displaying {@code go} first; {@code p} is 1 outside them. */
    private static final List<String> FOREVER = List.of(
                    "(let loop () (loop))",
                    // Tail calls between procedures go through the trampoline.
                    "(begin (define (ping) (pong)) (define (pong) (ping)) (ping))",
                    // Non-tail recursion past the spill depth, again and again.
                    "(begin (define (deep n) (if (= n 0) 0 (+ 1 (deep (- n 1))))) (let loop () (deep 100000) (loop)))",
                    // A handler can't catch an interrupt.
                    "(guard (e (#t 'caught)) (with-exception-handler (lambda (e) 'handled) (lambda () (let loop () (loop)))))",
                    "(parameterize ((p 2)) (dynamic-wind (lambda () #f) (lambda () (let loop () (loop))) (lambda () (display \"after\"))))",
                    "(let ((v (make-vector 100000 0))) (let loop () (vector-fill! v 1) (vector->list v) (loop)))",
                    "(let loop ((k 0)) (call/cc (lambda (c) (c k))) (loop (+ k 1)))");

    @Test
    public void anInterruptStopsTheEvaluationAndTheContextGoesOn() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = SchemeMain.newContext(List.of()).out(out).build()) {
            AtomicInteger givenUp = new AtomicInteger();
            Interrupter interrupter = Interrupter.of(context, givenUp::incrementAndGet);
            eval(context, "(define p (make-parameter 1))");
            for (String program : FOREVER) {
                out.reset();
                PolyglotException stopped = interrupt(context, interrupter, out, "(begin (display \"go\") (flush-output-port) " + program + ")");
                assertTrue(program + ": " + stopped, stopped.isInterrupted());
                assertFalse(program, stopped.isCancelled());
                assertEquals(program, "go", out.toString(StandardCharsets.UTF_8));
                out.reset();
                eval(context, "(p) (+ 1 2)");
                assertEquals(program, "1\n3\n", out.toString(StandardCharsets.UTF_8));
            }
            assertEquals(0, givenUp.get());
        }
    }

    /** Evaluates {@code text} on a thread of its own, interrupts it once it displays, and returns what it threw. */
    private static PolyglotException interrupt(Context context, Interrupter interrupter, ByteArrayOutputStream out, String text) throws InterruptedException {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        interrupter.begin();
        Thread evaluation = new Thread(() -> {
            try {
                eval(context, text);
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        evaluation.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (out.size() == 0 && evaluation.isAlive()) {
            if (System.nanoTime() > deadline) {
                fail(text + ": didn't start");
            }
            Thread.sleep(10);
        }
        interrupter.request();
        evaluation.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        assertFalse(text + ": not interrupted", evaluation.isAlive());
        interrupter.end();
        assertNotNull(text + ": ended without an exception", thrown.get());
        if (!(thrown.get() instanceof PolyglotException e)) {
            throw new AssertionError(text, thrown.get());
        }
        return e;
    }

    /** A REPL input: it writes the values of its forms. */
    private static void eval(Context context, String text) {
        context.eval(Source.newBuilder(SchemeMain.LANGUAGE, text, "<repl>").interactive(true).cached(false).buildLiteral());
    }

    @Test
    public void ctrlCInterruptsOnlyDuringAnEvaluation() throws InterruptedException {
        AtomicInteger interrupts = new AtomicInteger();
        Interrupter interrupter = new Interrupter(interrupts::incrementAndGet, () -> fail("gave up"), Duration.ofHours(1));
        interrupter.request();
        interrupter.begin();
        interrupter.end();
        assertEquals("no evaluation, no interrupt", 0, interrupts.get());
        interrupter.begin();
        interrupter.request();
        interrupter.end();
        assertEquals(1, interrupts.get());
        interrupter.request();
        assertEquals("the evaluation is over", 1, interrupts.get());
    }

    /** Ctrl-C ending a line Scheme code reads: the read returns once the interrupt waits for the evaluation. */
    @Test
    public void ctrlCDuringAReadWaitsForTheInterruptToStart() throws InterruptedException {
        CountDownLatch submitted = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        Interrupter interrupter = new Interrupter(() -> {
            submitted.countDown();
            try {
                stopped.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, () -> fail("gave up"), Duration.ofHours(1));
        interrupter.begin();
        interrupter.requestFromEvaluation();
        assertEquals("the interrupt has started", 0, submitted.getCount());
        stopped.countDown();
        interrupter.end();
    }

    @Test
    public void ctrlCGivesUpOnAnInterruptThatWaitsTooLong() throws InterruptedException {
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicInteger givenUp = new AtomicInteger();
        Runnable waits = () -> {
            try {
                stopped.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        // Within its patience, a second Ctrl-C does nothing more.
        Interrupter patient = new Interrupter(waits, givenUp::incrementAndGet, Duration.ofHours(1));
        patient.begin();
        patient.request();
        patient.request();
        assertEquals(0, givenUp.get());
        // Past it, Ctrl-C gives up.
        Interrupter impatient = new Interrupter(waits, givenUp::incrementAndGet, Duration.ZERO);
        impatient.begin();
        impatient.request();
        impatient.request();
        assertEquals(1, givenUp.get());
        stopped.countDown();
        patient.end();
        impatient.end();
    }
}
