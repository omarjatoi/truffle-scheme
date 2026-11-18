package scheme.test;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.Assume;
import org.junit.Test;

import com.oracle.truffle.api.debug.Breakpoint;
import com.oracle.truffle.api.debug.DebugStackFrame;
import com.oracle.truffle.api.debug.DebugValue;
import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.debug.DebuggerSession;
import com.oracle.truffle.api.debug.SuspendAnchor;
import com.oracle.truffle.api.debug.SuspendedEvent;
import com.oracle.truffle.api.debug.SuspensionFilter;
import com.oracle.truffle.api.source.SourceSection;

/**
 * Truffle's debugger stepping through Scheme code at statement granularity (design.md D13, "Tools"):
 * step into, over and out, through ordinary calls, a self tail call's loop, a tail call to another
 * procedure, a macro's expansion, {@code call/cc}, a prelude procedure that calls back (an internal
 * frame the debugger skips), {@code eval}'s code (located at the call of eval) and a let's body.
 * Each stop is {@code name:line @depth}, and a stop after a call (the debugger's
 * {@code SuspendAnchor.AFTER}) also has the call's value, {@code name:line=value @depth}.
 *
 * <p>Not under torture: there every non-tail call suspends its frame and resumes it (a Bytecode DSL
 * yield and resume), which Truffle's debugger takes for a generator's suspension (design.md D13,
 * "Yields and tools").
 */
public class SteppingTest {

    private static final String SCHEME = "scheme";

    private static final String PROGRAM = """
                    (import (scheme base) (scheme write) (scheme eval))
                    (define (sq x)
                      (* x x))
                    (define (sum-sq a b)
                      (+ (sq a)
                         (sq b)))
                    (define (count i acc)
                      (if (= i 0)
                          acc
                          (count (- i 1) (+ acc i))))
                    (define-syntax twice
                      (syntax-rules () ((_ e) (begin e e))))
                    (define (mac x)
                      (twice (sq x))
                      (sq x))
                    (define (esc)
                      (call/cc (lambda (k)
                        (sq 2)
                        (k 5)
                        (sq 3))))
                    (sum-sq 3 4)
                    (count 3 0)
                    (mac 2)
                    (esc)
                    (display (map sq '(1 2)))
                    (display (eval '(let ((y 2)) (* y y)) (environment '(scheme base))))
                    (newline)
                    (define (top)
                      (let ((r (sq 2)))
                        (+ r 3)))
                    (top)
                    """;

    private static String stop(SuspendedEvent event) {
        DebugStackFrame top = event.getTopStackFrame();
        SourceSection section = event.getSourceSection();
        StringBuilder sb = new StringBuilder(top.getName()).append(':').append(section == null ? "?" : Integer.toString(section.getStartLine()));
        if (event.getSuspendAnchor() == SuspendAnchor.AFTER) {
            DebugValue value = event.getReturnValue();
            sb.append('=').append(value == null ? "?" : value.toDisplayString());
        }
        int depth = 0;
        for (@SuppressWarnings("unused")
        DebugStackFrame frame : event.getStackFrames()) {
            depth++;
        }
        return sb.append(" @").append(depth).toString();
    }

    /**
     * Runs {@link #PROGRAM} with a breakpoint on {@code line}; at each stop, the next of
     * {@code steps} ({@code i} into, {@code o} over, {@code u} out), then continue.
     */
    private static List<String> steps(int line, String steps) {
        return steps(line, steps, false);
    }

    /**
     * {@link #steps(int, String)}, with the stepping filter a client sets to stop only where there
     * is a source location to show ({@code SuspensionFilter.sourceSectionAvailableOnly}) if
     * {@code located}.
     */
    private static List<String> steps(int line, String steps, boolean located) {
        Assume.assumeFalse("torture mode", Boolean.getBoolean("scheme.torture"));
        Source source = Source.newBuilder(SCHEME, PROGRAM, "step.scm").buildLiteral();
        List<String> stops = new ArrayList<>();
        try (Context context = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).build()) {
            try (DebuggerSession session = Debugger.find(context.getEngine()).startSession(event -> {
                char step = stops.size() < steps.length() ? steps.charAt(stops.size()) : 'c';
                stops.add(stop(event));
                switch (step) {
                    case 'i' -> event.prepareStepInto(1);
                    case 'o' -> event.prepareStepOver(1);
                    case 'u' -> event.prepareStepOut(1);
                    default -> event.prepareContinue();
                }
            })) {
                if (located) {
                    session.setSteppingFilter(SuspensionFilter.newBuilder().sourceSectionAvailableOnly(true).build());
                }
                session.install(Breakpoint.newBuilder(source.getURI()).lineIs(line).build());
                context.eval(source);
            }
        }
        return stops;
    }

    @Test
    public void stepIntoCallsAndBackOutOfThem() {
        // Each stop after a call has its value: the debugger stops at the caller's call (a CallTag).
        assertEquals(List.of("<program>:21 @2", "sum-sq:5 @3", "sq:3 @4", "sum-sq:5=9 @3", "sq:3 @4", "sum-sq:6=16 @3", "<program>:21=25 @2", "<program>:22 @2"),
                        steps(21, "iiiiiii"));
    }

    @Test
    public void stepOverAndOut() {
        assertEquals(List.of("<program>:21 @2", "sum-sq:5 @3", "<program>:21=25 @2", "<program>:22 @2", "<program>:23 @2", "<program>:24 @2"), steps(21, "ioooo"));
        assertEquals(List.of("<program>:21 @2", "sum-sq:5 @3", "sq:3 @4", "sum-sq:5=9 @3", "<program>:21=25 @2"), steps(21, "iiuu"));
    }

    @Test
    public void aSelfTailCallLoopsInOneFrame() {
        // count's tail call jumps back to its start (plan.md M7-02): one frame, a stop per iteration.
        assertEquals(List.of("<program>:22 @2", "count:8 @3", "count:8 @3", "count:8 @3", "count:8 @3", "<program>:22=6 @2", "<program>:23 @2"), steps(22, "iiiiii"));
    }

    @Test
    public void aTailCallReplacesItsCallersFrame() {
        // The macro's two copies of (sq x) stop at the use's own line. mac's last call is a tail
        // call: mac returns it to its caller (a TailCall, shown as the call it stands for, D13),
        // which makes it, so sq runs in mac's place, one frame up.
        assertEquals(List.of("<program>:23 @2", "mac:14 @3", "sq:3 @4", "mac:14=4 @3", "mac:14 @3", "sq:3 @4", "mac:14=4 @3", "mac:15 @3",
                        "<program>:23=#<tail call (sq 2)> @2", "sq:3 @3", "<program>:23=4 @2", "<program>:24 @2"), steps(23, "iiiiiiiiiii"));
    }

    @Test
    public void stepIntoCallCC() {
        // call/cc is a tail call of esc's; its receiver runs below call/cc's frame. Stepping on
        // across the escape (k 5) is a known limitation (design.md D13, "Yields and tools").
        assertEquals(List.of("<program>:24 @2", "esc:17 @3", "<program>:24=#<tail call (call/cc #<procedure>)> @2", "<lambda@step.scm:17:12>:18 @4", "sq:3 @5",
                        "<lambda@step.scm:17:12>:18=4 @4", "<lambda@step.scm:17:12>:19 @4"), steps(24, "iiiiii"));
    }

    @Test
    public void preludeFramesAreSkipped() {
        // map is the prelude's (an internal source): stepping goes from the program into sq, and
        // back out at map's call.
        assertEquals(List.of("<program>:25 @2", "sq:3 @4", "<program>:25=1 @3", "sq:3 @4", "<program>:25=4 @3", "<program>:26 @2"), steps(25, "iiiii"));
    }

    @Test
    public void evaluatedCodeStopsAtTheCallOfEval() {
        // eval's code, made from a datum, has no source of its own: it is located at the call of
        // eval (design.md D13), where its two statements (the let and its body) stop, with a
        // client's filter for located stops too.
        List<String> stops = List.of("<program>:26 @2", "eval:26 @3", "eval:26 @3", "<program>:26=4 @2", "<program>:27 @2");
        assertEquals(stops, steps(26, "iiii"));
        assertEquals(stops, steps(26, "iiii", true));
    }

    @Test
    public void aLetsBodyIsAStatement() {
        // top's let binds r, then its body (+ r 1) is the next statement.
        assertEquals(List.of("<program>:31 @2", "top:29 @3", "sq:3 @4", "top:29=4 @3", "top:30 @3", "<program>:31=7 @2"), steps(31, "iiiio"));
    }
}
