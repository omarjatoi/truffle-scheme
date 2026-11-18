package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.Value;
import org.junit.Test;

/**
 * M3-10: errors and tools through Truffle (design.md D13). Errors report their type and location
 * through interop, and a Truffle instrument ({@link EventRecorder}) sees Scheme statements and
 * calls with their source locations. Under {@code //tests:unit_torture} these run with a
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

    // A program with a top-level variable, two procedures (one making a closure) and an eval.
    private static final String LOCATED = """
                    (import (scheme base) (scheme write) (scheme eval))
                    (define base 10)
                    (define (scale x y)
                      (* x y base))
                    (define (adder n) (lambda (m) (+ n m)))
                    (display (scale 2 3))
                    (display ((adder 1) 2))
                    (display (eval '(if #t (+ 1 2)) (environment '(scheme base))))
                    """;

    /**
     * What the coverage tool needs (design.md D13, "Tools"): every root and statement it loads
     * belongs to a root with a source section, by which it groups them (else its JSON and LCOV
     * output fail): the program's root is the program, a top-level definition is a statement at
     * its form (a procedure's at its name, apart from its root's), and code that eval compiles from
     * a datum is located at the call of eval.
     */
    @Test
    public void everyLoadedStatementHasALocatedRoot() {
        try (Context context = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).build()) {
            EventRecorder recorder = context.getEngine().getInstruments().get(EventRecorder.ID).lookup(EventRecorder.class);
            context.eval(Source.newBuilder(SCHEME, LOCATED, "located.scm").buildLiteral());
            List<String> loaded = recorder.loaded.stream().filter(e -> e.contains("located.scm")).toList();
            assertTrue(loaded.toString(), loaded.stream().noneMatch(e -> e.endsWith("@none")));
            assertTrue(loaded.toString(), loaded.contains("statement located.scm 2:1 (define base 10) in <program>@1:1"));
            assertTrue(loaded.toString(), loaded.stream().anyMatch(e -> e.startsWith("root located.scm 1:1 (import") && e.endsWith(" in <program>@1:1")));
            assertTrue(loaded.toString(), loaded.contains("statement located.scm 3:10 scale in <program>@1:1"));
            assertTrue(loaded.toString(), loaded.contains("statement located.scm 4:3 (* x y base) in scale@3:1"));
            assertTrue(loaded.toString(), loaded.contains("statement located.scm 5:31 (+ n m) in <lambda@located.scm:5:19>@5:19"));
            // eval's code: its root and its statement, at the call of eval.
            String call = "located.scm 8:10 (eval '(if #t (+ 1 2)) (environment '(scheme base)))";
            assertTrue(loaded.toString(), loaded.contains("root " + call + " in eval@8:10"));
            assertTrue(loaded.toString(), loaded.contains("statement " + call + " in eval@8:10"));
            assertEquals(0, recorder.sourceless);
        }
    }

    /**
     * Code with no location has no statements or calls (design.md D13): eval's code when the host
     * calls eval, so no Scheme call is its location. A tool would have nothing to show for it, and
     * Truffle's debugger warns of a tagged node without a source section. (Its root keeps the root
     * tag the Bytecode DSL gives every root, which {@code sandbox.MaxStackFrames} counts.)
     */
    @Test
    public void unlocatedCodeHasNoEvents() {
        try (Context context = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).build()) {
            EventRecorder recorder = context.getEngine().getInstruments().get(EventRecorder.ID).lookup(EventRecorder.class);
            Value eval = context.eval(SCHEME, "(import (scheme eval)) eval");
            Value environment = context.eval(SCHEME, "(environment '(scheme base))");
            Value datum = context.eval(SCHEME, "'(let loop ((i 0)) (if (= i 3) i (loop (+ i 1))))");
            assertEquals(3, eval.execute(datum, environment).asInt());
            assertEquals(0, recorder.sourceless);
        }
    }

    /**
     * A root body ({@code RootBodyTag}) starts once the procedure's arguments are in its variables,
     * as Truffle asks ("when the implementation copies {@code Frame#getArguments()} into frame
     * slots, it should do it before this node"), so a tool entering it sees them.
     */
    @Test
    public void rootBodiesSeeTheirArguments() {
        try (Context context = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).build()) {
            EventRecorder recorder = context.getEngine().getInstruments().get(EventRecorder.ID).lookup(EventRecorder.class);
            context.eval(Source.newBuilder(SCHEME, LOCATED, "bodies.scm").buildLiteral());
            List<String> bodies = recorder.bodies;
            assertTrue(bodies.toString(), bodies.stream().anyMatch(b -> b.startsWith("scale ") && b.contains("x=2") && b.contains("y=3")));
            assertTrue(bodies.toString(), bodies.stream().anyMatch(b -> b.startsWith("adder ") && b.contains("n=1")));
            assertTrue(bodies.toString(), bodies.stream().anyMatch(b -> b.startsWith("<lambda@bodies.scm:5:19> ") && b.contains("m=2") && b.contains("n=1")));
        }
    }

    /**
     * M7-07: the language reports the Scheme objects it allocates to an allocation listener (what
     * {@code --memtracer} attaches), with an estimate of their size.
     */
    @Test
    public void allocationsAreReported() {
        Source source = Source.newBuilder(SCHEME, """
                        (define-record-type point (make-point x y) point? (x point-x) (y point-y))
                        (define (rest . xs) xs)
                        (define (adder n) (lambda (m) (+ n m)))
                        (define big (* 4611686018427387904 4))
                        (define kept
                          (list (cons 1 2) (vector 1 2) (make-string 2 #\\a) (string-append "a" "b") (rest 1 2)
                                (adder 1) (make-point 1 2) (* big big) (bytevector 1 2) (list-copy '(1 2 3))))
                        (vector-length (make-vector 1000 0))
                        """, "allocations.scm").buildLiteral();
        try (Context context = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).build()) {
            context.initialize(SCHEME);
            AllocationRecorder recorder = context.getEngine().getInstruments().get(AllocationRecorder.ID).lookup(AllocationRecorder.class);
            assertEquals(1000, context.eval(source).asInt());
            Set<String> types = new TreeSet<>();
            for (AllocationRecorder.Allocation allocation : recorder.allocations) {
                types.add(allocation.type());
                assertTrue(allocation.toString(), allocation.size() > 0);
            }
            assertTrue(types.toString(), types.containsAll(List.of("SPair", "SVector", "SString", "SClosure", "SRecord", "SBigInteger", "SBytevector")));
            assertTrue(recorder.allocations.toString(), recorder.allocations.stream().anyMatch(a -> a.type().equals("SVector") && a.size() >= 8000));
        }
    }
}
