package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
import org.junit.Test;

import com.oracle.truffle.api.debug.Breakpoint;
import com.oracle.truffle.api.debug.DebugStackFrame;
import com.oracle.truffle.api.debug.DebugStackTraceElement;
import com.oracle.truffle.api.debug.DebugValue;
import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.debug.DebuggerSession;
import com.oracle.truffle.api.debug.SuspendedEvent;

/**
 * M6-04: frames that live on the heap, in continuation segments, are part of the stack that tools
 * see. Past the spill depth (design.md "Continuations"), the frames waiting for a value are in the
 * root prompt's pending segments rather than on the Java stack, and so are the frames of a resumed
 * continuation. An uncaught error unwinds through them, so the guest stack trace has every frame;
 * a suspended debugger sees them as the prompt's asynchronous stack, right after the frames on the
 * Java stack. Under {@code mx tests torture} every frame has been captured and resumed, and the
 * traces must be the same.
 */
public class SpilledFramesTest {

    private static final String SCHEME = "scheme";

    /** Far deeper than the spill depth (50 by default). */
    private static final int DEPTH = 1000;

    // %raise-uncaught throws an error that no call site raises: the trace is the live frames'.
    private static final String DEEP_ERROR = """
                    (define (down n)
                      (if (= n 0)
                          (+ 1 (%raise-uncaught 'bottom))
                          (+ 1 (down (- n 1)))))
                    (down DEPTH)
                    """.replace("DEPTH", Integer.toString(DEPTH));

    /** "name:line" for each guest frame of the error, innermost first ("name" without a location). */
    private static List<String> frames(PolyglotException e) {
        List<String> result = new ArrayList<>();
        for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
            if (frame.isGuestFrame()) {
                SourceSection s = frame.getSourceLocation();
                result.add(frame.getRootName() + (s == null ? "" : ":" + s.getStartLine()));
            }
        }
        return result;
    }

    private static PolyglotException error(Context context, Source source) {
        PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval(source));
        assertTrue(e.getMessage(), e.isGuestException());
        return e;
    }

    /** {@code count} copies of {@code element}. */
    private static List<String> times(int count, String element) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(element);
        }
        return result;
    }

    /** {@code frames} with each run of equal elements as one, "element *count" if more than one. */
    private static List<String> runs(List<String> frames) {
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < frames.size()) {
            int j = i;
            while (j < frames.size() && frames.get(j).equals(frames.get(i))) {
                j++;
            }
            result.add(frames.get(i) + (j - i > 1 ? " *" + (j - i) : ""));
            i = j;
        }
        return result;
    }

    @Test
    public void guestStackTraceHasEverySpilledFrame() {
        Source source = Source.newBuilder(SCHEME, DEEP_ERROR, "deep.scm").buildLiteral();
        try (Context context = Context.create(SCHEME)) {
            List<String> expected = new ArrayList<>();
            // The innermost frame is where %raise-uncaught ran in place (so it has no call location);
            // each frame above it waits at the recursive call on line 4, and the program at its call
            // on line 5.
            expected.add("down");
            expected.addAll(times(DEPTH, "down:4"));
            expected.add("<program>:5");
            expected.add("<prompt>:1");
            assertEquals(runs(expected), runs(frames(error(context, source))));
        }
    }

    @Test
    public void veryDeepTracesStopAfterTenThousandHeapFrames() {
        // The error is raised (D8) and nothing handles it: the trace starts in the prelude's raise.
        // Like a JVM's, the trace of an error 100,000 frames deep stops: after the frames on the
        // Java stack, it has the 10,000 innermost frames on the heap, then the prompt.
        Source source = Source.newBuilder(SCHEME, "(define (down n) (if (= n 0) (car '()) (+ 1 (down (- n 1)))))\n(down 100000)\n", "deeper.scm").buildLiteral();
        try (Context context = Context.create(SCHEME)) {
            List<String> frames = frames(error(context, source));
            List<String> runs = runs(frames.subList(frames.indexOf("down:1"), frames.size()));
            assertEquals(runs.toString(), 2, runs.size());
            assertEquals("<prompt>:1", runs.get(1));
            int down = frames.size() - 1 - frames.indexOf("down:1");
            assertTrue(runs.toString(), down >= 10_000 && down < 10_050);
        }
    }

    @Test
    public void resumedContinuationHasEveryFrame() {
        // The continuation captured at the bottom of the recursion is resumed twice from the top
        // level, so its frames are all on the heap; the second time, bottom fails.
        Source source = Source.newBuilder(SCHEME, """
                        (define k #f)
                        (define (bottom v) (if (= v 2) (%raise-uncaught 'bottom) v))
                        (define (down n)
                          (if (= n 0)
                              (+ 1 (bottom (call/cc (lambda (c) (set! k c) 0))))
                              (+ 1 (down (- n 1)))))
                        (define r (down DEPTH))
                        (if (< r (+ DEPTH 3)) (k (- r DEPTH)))
                        """.replace("DEPTH", Integer.toString(DEPTH)), "resumed.scm").buildLiteral();
        try (Context context = Context.create(SCHEME)) {
            List<String> expected = new ArrayList<>();
            // %raise-uncaught runs in place in bottom's frame, which therefore has no call location.
            expected.add("bottom");
            expected.add("down:5");
            expected.addAll(times(DEPTH, "down:6"));
            expected.add("<program>:7");
            expected.add("<prompt>:1");
            assertEquals(runs(expected), runs(frames(error(context, source))));
        }
    }

    @Test
    public void suspendedDebuggerSeesEverySpilledFrame() {
        // The breakpoint's line calls only primitives, so under torture it is hit once too. It has a
        // line of its own: a debugger suspends at each statement that starts on the line.
        Source source = Source.newBuilder(SCHEME, """
                        (define (down n)
                          (if (= n 0)
                              (begin
                                (car (cons n n))
                                0)
                              (+ 1 (down (- n 1)))))
                        (down DEPTH)
                        """.replace("DEPTH", Integer.toString(DEPTH)), "debug-deep.scm").buildLiteral();
        List<List<String>> suspensions = new ArrayList<>();
        try (Context context = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).build()) {
            Debugger debugger = Debugger.find(context.getEngine());
            try (DebuggerSession session = debugger.startSession(event -> {
                suspensions.add(describe(event, true));
                event.prepareContinue();
            })) {
                Breakpoint breakpoint = Breakpoint.newBuilder(source.getURI()).lineIs(4).build();
                session.install(breakpoint);
                assertEquals(DEPTH, context.eval(source).asInt());
                assertEquals(1, breakpoint.getHitCount());
            }
        }
        List<String> expected = new ArrayList<>();
        // Every activation of down, innermost first, each showing its own n; then the program. The
        // prompt (the frames on the Java stack end there) is in between, somewhere.
        for (int n = 0; n <= DEPTH; n++) {
            expected.add("down:" + (n == 0 ? 4 : 6) + " n=" + n);
        }
        expected.add("<program>:7");
        List<String> stack = suspensions.get(0);
        int prompt = stack.indexOf("<prompt>");
        // Fewer frames than the spill depth are on the Java stack: the rest are on the heap.
        assertTrue(stack.toString(), prompt > 0 && prompt < 50);
        stack.remove(prompt);
        assertEquals(expected, stack);
    }

    @Test
    public void everyFrameOnceAtEveryLevelDownAndBackUp() {
        // A frame is on the Java stack or pending on the heap, never both and never neither: at each
        // level on the way down (spilling every 50 frames) and on the way back up (resuming each
        // pending frame), the stack is exactly the live activations. Lines 2 and 6 call only
        // primitives, so under torture each is hit once per level too.
        int depth = 200;
        Source source = Source.newBuilder(SCHEME, """
                        (define (down n)
                          (car (cons n n))
                          (if (= n 0)
                              0
                              (let ((r (down (- n 1))))
                                (car (cons r n))
                                (+ 1 r))))
                        (down DEPTH)
                        """.replace("DEPTH", Integer.toString(depth)), "down-and-up.scm").buildLiteral();
        List<List<String>> suspensions = new ArrayList<>();
        try (Context context = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).build()) {
            Debugger debugger = Debugger.find(context.getEngine());
            try (DebuggerSession session = debugger.startSession(event -> {
                // Before the first spill and after the last resume, the program's frame is on the Java
                // stack and nothing is pending: no asynchronous stack (and Truffle 25.5's
                // getAsynchronousStacks() throws if there is none, so don't ask).
                boolean pending = true;
                for (DebugStackFrame frame : event.getStackFrames()) {
                    pending &= !frame.getName().equals("<program>");
                }
                List<String> stack = describe(event, pending);
                stack.remove("<prompt>");
                suspensions.add(stack);
                event.prepareContinue();
            })) {
                session.install(Breakpoint.newBuilder(source.getURI()).lineIs(2).build());
                session.install(Breakpoint.newBuilder(source.getURI()).lineIs(6).build());
                assertEquals(depth, context.eval(source).asInt());
            }
        }
        List<List<String>> expected = new ArrayList<>();
        for (int n = depth; n >= 0; n--) {
            expected.add(live(n, 2, depth));
        }
        for (int n = 1; n <= depth; n++) {
            expected.add(live(n, 6, depth));
        }
        assertEquals(expected.size(), suspensions.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals("suspension " + i, runs(expected.get(i)), runs(suspensions.get(i)));
        }
    }

    /** The live frames when down(n) is at {@code line}: it, each caller waiting on line 5, the program. */
    private static List<String> live(int n, int line, int depth) {
        List<String> stack = new ArrayList<>();
        stack.add("down:" + line + " n=" + n);
        for (int m = n + 1; m <= depth; m++) {
            stack.add("down:5 n=" + m);
        }
        stack.add("<program>:8");
        return stack;
    }

    /**
     * The suspended stack as a debugger front end shows it, innermost first: the frames on the Java
     * stack, then, if {@code pending}, the one asynchronous stack (the frames on the heap).
     * "name:line n=value".
     */
    private static List<String> describe(SuspendedEvent event, boolean pending) {
        List<String> stack = new ArrayList<>();
        for (DebugStackFrame frame : event.getStackFrames()) {
            if (frame.getSourceSection() == null) {
                stack.add(frame.getName());
            } else {
                DebugValue n = frame.getScope().getDeclaredValue("n");
                stack.add(frame.getName() + ":" + frame.getSourceSection().getStartLine() + (n == null ? "" : " n=" + n.toDisplayString()));
            }
        }
        if (!pending) {
            return stack;
        }
        List<List<DebugStackTraceElement>> asynchronous = event.getAsynchronousStacks();
        assertEquals(1, asynchronous.size());
        for (DebugStackTraceElement element : asynchronous.get(0)) {
            assertNotNull(element.getName(), element.getSourceSection());
            DebugValue n = element.getScope().getDeclaredValue("n");
            stack.add(element.getName() + ":" + element.getSourceSection().getStartLine() + (n == null ? "" : " n=" + n.toDisplayString()));
        }
        return stack;
    }
}
