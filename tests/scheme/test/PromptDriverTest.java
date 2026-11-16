package scheme.test;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.junit.Test;

/**
 * The prompt driver's handling of errors that escape the frames it calls (design.md D8): such an
 * error is raised for the innermost pending frame, and a control transfer out of that raise leaves
 * no state behind that changes how a later error is raised.
 */
public class PromptDriverTest {

    private static final String SCHEME = "scheme";

    private static String run(String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Spills past 50 nested calls, however much Java stack there is (plan.md M8-01).
        try (Context context = Context.newBuilder(SCHEME).allowExperimentalOptions(true).option("scheme.SpillDepth", "50").out(out).build()) {
            context.eval(SCHEME, program);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /**
     * {@code g}'s frame waits for a recursion deep enough to spill, so the root prompt resumes it
     * from a segment, and its tail call to {@code car} fails in the driver. That error belongs to
     * the frame waiting for {@code g}, inside prompt {@code p}, so a handler that aborts to {@code p}
     * finds it. The first time, the handler jumps to a full continuation from inside that raise;
     * the second time, the same error must again be raised inside {@code p}, not one frame further
     * out (past {@code p}'s marker), where the abort would find no prompt.
     * <p>
     * The prelude's {@code raise} tail-calls its {@code let*} body before it calls a handler, so the
     * driver's call to it returns before the handler runs. This test installs a {@code raise} that
     * calls the handler in its own frame, so the jump interrupts the driver's call to raise.
     */
    @Test
    public void continuationJumpOutOfARaiseLeavesNoStateBehind() {
        String program = "(define k #f) (define jumps 0)" +
                        "(define (direct-raise obj) (handler obj) (%raise-uncaught obj))" +
                        "(define (deep i) (if (= i 0) '() (cons i (deep (- i 1)))))" +
                        "(define (g) (car (length (deep 1000))))" +
                        "(define (handler e)" +
                        "  (set! jumps (+ jumps 1))" +
                        "  (cond ((= jumps 1) (k 'again))" +
                        "        ((= jumps 2) (%capture-to-prompt 'p (lambda (c) 'caught)))" +
                        "        (else (%raise-uncaught e))))" +
                        "(%set-raise! direct-raise)" +
                        "(define (attempt)" +
                        "  (call/cc (lambda (c) (set! k c)))" +
                        "  (with-exception-handler handler" +
                        "    (lambda () (%call-with-prompt 'p (lambda () (list (g)))))))" +
                        "(let ((r (attempt))) (write (list jumps r)))";
        assertEquals("(2 caught)", run(program));
    }
}
