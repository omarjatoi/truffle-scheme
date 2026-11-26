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

    // Reads and writes of a global, of a parameter in its procedure and captured in a closure
    // (boxed, as it is assigned), of a fixed letrec* variable in its own lambda (the running
    // closure), and of or's temporary, which no code the user wrote can refer to.
    private static final String VARIABLES = """
                    (import (scheme base) (scheme write))
                    (define g 1)
                    (define (counter n)
                      (lambda ()
                        (set! n (+ n g))
                        n))
                    (define c (counter 10))
                    (set! g 2)
                    (display (c))
                    (define (self) (letrec ((f (lambda () f))) (f)))
                    (display (procedure? (self)))
                    (display (or #f g))
                    """;

    /**
     * M13-14 (design.md D13, "Variables' reads and writes"): every read of a variable the user's
     * code can name is tagged {@code ReadVariableTag}, and every assignment and top-level definition
     * {@code WriteVariableTag}, with a node object that names the variable, as Truffle's tags ask.
     * A compiler temporary or a macro's own variable has no tag.
     */
    @Test
    public void instrumentsSeeVariablesByName() {
        try (Context context = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).build()) {
            VariableRecorder recorder = context.getEngine().getInstruments().get(VariableRecorder.ID).lookup(VariableRecorder.class);
            context.eval(Source.newBuilder(SCHEME, VARIABLES, "variables.scm").buildLiteral());
            Set<String> events = new TreeSet<>(recorder.events);
            Set<String> expected = new TreeSet<>(List.of(
                            "write g 2:1 (define g 1)",
                            "write counter 3:10 counter",
                            "write n 5:5 (set! n (+ n g))",
                            "read n 5:16 n = 10",
                            "read g 5:18 g = 2",
                            "read n 6:5 n = 12",
                            "read counter 7:12 counter = #<procedure counter>",
                            "write c 7:1 (define c (counter 10))",
                            "write g 8:1 (set! g 2)",
                            "read display 9:2 display = #<procedure display>",
                            "read c 9:11 c = #<procedure>",
                            "write self 10:10 self",
                            "read f 10:39 f = #<procedure f>",
                            "read f 10:45 f = #<procedure f>",
                            "read display 11:2 display = #<procedure display>",
                            "read procedure? 11:11 procedure? = #<procedure procedure?>",
                            "read self 11:23 self = #<procedure self>",
                            "read display 12:2 display = #<procedure display>",
                            "read g 12:17 g = 2"));
            assertEquals(expected, events);
            // Every one of them loads with its name, before it runs.
            Set<String> loaded = new TreeSet<>(recorder.loaded);
            for (String event : expected) {
                String node = event.contains(" = ") ? event.substring(0, event.indexOf(" = ")) : event;
                assertTrue(node + " in " + loaded, loaded.contains(node));
            }
        }
    }

    /**
     * A variable's reads and writes keep their names in frames a continuation resumes: {@code v}'s
     * assignment takes the value of a {@code call/cc} whose continuation is re-entered twice, each
     * time in a copy of the frame (AGENTS.md rule 3), and under {@code //tests:unit_torture} at
     * every non-tail call too.
     */
    @Test
    public void variablesKeepTheirNamesInResumedFrames() {
        Source source = Source.newBuilder(SCHEME, """
                        (import (scheme base) (scheme write))
                        (define k #f)
                        (define n 0)
                        (define (f)
                          (let ((v 0))
                            (set! v (call/cc (lambda (c) (set! k c) 1)))
                            v))
                        (define r (f))
                        (set! n (+ n 1))
                        (if (< n 3) (k (+ n 10)))
                        (display r)
                        """, "resumed.scm").buildLiteral();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            VariableRecorder recorder = context.getEngine().getInstruments().get(VariableRecorder.ID).lookup(VariableRecorder.class);
            context.eval(source);
            assertEquals("12", out.toString());
            List<String> events = recorder.events;
            for (String event : List.of("read c 6:42 c = #<procedure continuation>", "write k 6:34 (set! k c)", "write n 9:1 (set! n (+ n 1))", "read n 10:19 n = 2")) {
                assertTrue(event + " in " + events, events.contains(event));
            }
            // The assignment of v returns once with each value, and v is read once after each.
            String write = "write v 6:5 (set! v (call/cc (lambda (c) (set! k c) 1)))";
            assertEquals(events.toString(), 3, events.stream().filter(write::equals).count());
            assertEquals(events.toString(), 3, events.stream().filter(e -> e.startsWith("write r 8:1 ")).count());
            List<String> reads = events.stream().filter(e -> e.startsWith("read v ")).toList();
            assertEquals(List.of("read v 7:5 v = 1", "read v 7:5 v = 11", "read v 7:5 v = 12"), reads);
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
