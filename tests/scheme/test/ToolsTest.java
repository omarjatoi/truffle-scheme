package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
import org.junit.Test;

/**
 * M3-10: errors and tools through Truffle (design.md D13). Errors report their type and location
 * through interop, and a Truffle instrument ({@link EventRecorder}) sees Scheme statements and
 * calls with their source locations. Under {@code mx tests torture} these run with a
 * capture and resume at every non-tail call.
 */
public class ToolsTest {

    private static final String SCHEME = "scheme";

    private static PolyglotException error(String text) {
        try (Context context = Context.create(SCHEME)) {
            return assertThrows(PolyglotException.class, () -> context.eval(Source.create(SCHEME, text)));
        }
    }

    @Test
    public void syntaxErrorsAreParseErrors() {
        PolyglotException e = error("(display 1)\n(if)");
        assertTrue(e.isSyntaxError());
        assertFalse(e.isIncompleteSource());
        assertNotNull(e.getSourceLocation());
        assertEquals(2, e.getSourceLocation().getStartLine());
        // Reader errors in program text are parse errors too.
        PolyglotException stray = error("(display 1))");
        assertTrue(stray.isSyntaxError());
        assertFalse(stray.isIncompleteSource());
    }

    @Test
    public void unfinishedInputIsIncomplete() {
        for (String text : new String[]{"(define (f x)", "(display \"abc", "'", "#(1 2"}) {
            PolyglotException e = error(text);
            assertTrue(text, e.isSyntaxError());
            assertTrue(text, e.isIncompleteSource());
        }
    }

    @Test
    public void runtimeErrorsAreRuntimeErrorsWithALocation() {
        PolyglotException e = error("(define (f x) (car x))\n(f 1)");
        assertFalse(e.isSyntaxError());
        assertFalse(e.isIncompleteSource());
        SourceSection location = e.getSourceLocation();
        assertNotNull(location);
        assertEquals(2, location.getStartLine());
    }

    @Test
    public void instrumentsSeeStatementsAndCalls() {
        Source source = Source.newBuilder(SCHEME, """
                        (define (square x)
                          (* x x))
                        (display (square 3))
                        (newline)
                        """, "tools.scm").buildLiteral();
        try (Context context = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).build()) {
            EventRecorder recorder = context.getEngine().getInstruments().get(EventRecorder.ID).lookup(EventRecorder.class);
            context.eval(source);
            List<String> events = recorder.events.stream().filter(e -> e.contains("tools.scm")).toList();
            // square's body, and the top-level forms, are statements.
            assertTrue(events.toString(), events.contains("statement tools.scm 2:3 (* x x)"));
            assertTrue(events.toString(), events.contains("statement tools.scm 3:1 (display (square 3))"));
            assertTrue(events.toString(), events.contains("statement tools.scm 4:1 (newline)"));
            // Calls, in value and tail position.
            assertTrue(events.toString(), events.contains("call tools.scm 3:10 (square 3)"));
            assertTrue(events.toString(), events.contains("call tools.scm 3:1 (display (square 3))"));
            assertTrue(events.toString(), events.contains("call tools.scm 2:3 (* x x)"));
        }
    }
}
