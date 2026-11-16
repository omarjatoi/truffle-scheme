package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.Test;

import com.oracle.truffle.api.debug.Breakpoint;
import com.oracle.truffle.api.debug.DebugScope;
import com.oracle.truffle.api.debug.DebugStackFrame;
import com.oracle.truffle.api.debug.DebugStackTraceElement;
import com.oracle.truffle.api.debug.DebugValue;
import com.oracle.truffle.api.debug.DebugException;
import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.debug.DebuggerSession;
import com.oracle.truffle.api.debug.SuspendedEvent;

/**
 * Truffle's debugger on Scheme code (design.md D13), through the API the DAP server and the Chrome
 * inspector drive: a session attaches (every session installs an {@code AlwaysHalt} breakpoint, so
 * the language must provide that tag), line breakpoints suspend, stepping works, and parameters
 * show by name. A frame's scope shows the variables that Scheme code there can refer to (plan.md
 * M6-05): its own and the captured ones, boxes unboxed, no compiler temporaries; expressions
 * evaluate in a suspended frame; and every frame has a name.
 */
public class DebuggerTest {

    private static final String SCHEME = "scheme";

    // f calls only primitives: under torture, a non-tail call to a Scheme procedure (list, say)
    // would capture f's frame and resume it twice, hitting the breakpoint again.
    private static final String PROGRAM = """
                    (define (f a b)
                      (car (cons a b))
                      (+ a b))
                    (f 1 2)
                    """;

    /** The top frame's name and line, and the named values in its scope, innermost first. */
    private static String describe(SuspendedEvent event) {
        DebugStackFrame top = event.getTopStackFrame();
        StringBuilder sb = new StringBuilder();
        sb.append(top.getName()).append(':').append(top.getSourceSection().getStartLine());
        List<String> vars = new ArrayList<>();
        for (DebugScope scope = top.getScope(); scope != null; scope = scope.getParent()) {
            for (DebugValue v : scope.getDeclaredValues()) {
                vars.add(v.getName() + "=" + v.toDisplayString());
            }
        }
        return sb.append(' ').append(vars).toString();
    }

    @Test
    public void lineBreakpointsSteppingAndNamedParameters() {
        Source source = Source.newBuilder(SCHEME, PROGRAM, "debug.scm").buildLiteral();
        List<String> seen = new ArrayList<>();
        List<String> a = new ArrayList<>();
        try (Context context = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).build()) {
            Debugger debugger = Debugger.find(context.getEngine());
            try (DebuggerSession session = debugger.startSession(event -> {
                seen.add(describe(event));
                DebugValue value = event.getTopStackFrame().getScope().getDeclaredValue("a");
                a.add(value == null ? null : value.toDisplayString());
                if (seen.size() == 1) {
                    event.prepareStepOver(1);
                } else {
                    event.prepareContinue();
                }
            })) {
                Breakpoint breakpoint = Breakpoint.newBuilder(source.getURI()).lineIs(2).build();
                session.install(breakpoint);
                assertEquals(3, context.eval(source).asInt());
                assertEquals(1, breakpoint.getHitCount());
            }
        }
        // Suspended at the breakpoint in f, then on the next statement after stepping over, with
        // the parameters shown by their Scheme names (not local0, local1).
        assertEquals(List.of("f:2 [a=1, b=2]", "f:3 [a=1, b=2]"), seen);
        assertEquals(List.of("1", "1"), a);
    }

    @Test
    public void suspendNextExecutionHaltsInSchemeCode() {
        List<String> seen = new ArrayList<>();
        try (Context context = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).build()) {
            Debugger debugger = Debugger.find(context.getEngine());
            try (DebuggerSession session = debugger.startSession(event -> {
                assertNotNull(event.getSourceSection());
                seen.add(event.getSourceSection().getSource().getName());
                event.prepareContinue();
            })) {
                session.suspendNextExecution();
                assertEquals(3, context.eval(Source.newBuilder(SCHEME, "(+ 1 2)", "next.scm").buildLiteral()).asInt());
            }
        }
        assertEquals(List.of("next.scm"), seen);
    }

    /** The names and values in the frame's own scope (not its parents), in order. */
    private static List<String> variables(DebugStackFrame frame) {
        List<String> vars = new ArrayList<>();
        for (DebugValue v : frame.getScope().getDeclaredValues()) {
            vars.add(v.getName() + "=" + v.toDisplayString());
        }
        return vars;
    }

    /**
     * Evaluates the definitions, then runs the expression with a line breakpoint in the
     * definitions; the handler sees each hit. Returns the expression's value. (A line breakpoint
     * resolves to a statement of the procedures loaded so far: with the definitions run first, the
     * innermost lambda's statement is one of them.)
     */
    private static String runWithBreakpoint(String definitions, String name, int line, String expression, Consumer<SuspendedEvent> handler) {
        Source source = Source.newBuilder(SCHEME, definitions, name).buildLiteral();
        try (Context context = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).build()) {
            Debugger debugger = Debugger.find(context.getEngine());
            try (DebuggerSession session = debugger.startSession(event -> {
                handler.accept(event);
                event.prepareContinue();
            })) {
                context.eval(source);
                session.install(Breakpoint.newBuilder(source.getURI()).lineIs(line).build());
                return context.eval(SCHEME, expression).toString();
            }
        }
    }

    // count is assigned (so it is a Box, design.md D2) and captured; step is captured; n is the
    // parameter.
    private static final String COUNTER = """
                    (define (make-counter start)
                      (let ((count start) (step 2))
                        (lambda (n)
                          (set! count (+ count (* n step)))
                          count)))
                    (define c (make-counter 10))
                    """;

    @Test
    public void capturedAndAssignedVariablesShowWithTheirValues() {
        List<String> seen = new ArrayList<>();
        List<Boolean> writable = new ArrayList<>();
        String result = runWithBreakpoint(COUNTER, "counter.scm", 5, "(c 1)", event -> {
            DebugStackFrame top = event.getTopStackFrame();
            seen.add(top.getName() + " " + variables(top));
            DebugScope scope = top.getScope();
            writable.add(scope.getDeclaredValue("count").isWritable());
            writable.add(scope.getDeclaredValue("step").isWritable());
            // Writing an assigned variable sets its box: the procedure returns the new value.
            scope.getDeclaredValue("count").set(top.eval("100"));
        });
        assertEquals(List.of("<lambda@counter.scm:3:5> [n=1, count=12, step=2]"), seen);
        // Only the variables a program assigns are mutable (design.md D2); step is a copied value.
        assertEquals(List.of(true, false), writable);
        assertEquals("100", result);
    }

    @Test
    public void expressionsEvaluateInASuspendedFrame() {
        List<String> values = new ArrayList<>();
        String result = runWithBreakpoint(COUNTER, "eval.scm", 5, "(c 1)", event -> {
            DebugStackFrame top = event.getTopStackFrame();
            // Parameters, captured and boxed variables, and the interaction environment's globals.
            values.add(top.eval("(+ count n step)").toDisplayString());
            values.add(top.eval("(list n (let ((m 3)) (* m step)) (procedure? make-counter))").toDisplayString());
            // set! of an assigned variable writes its box; of any other variable, it is an error.
            top.eval("(set! count (* count 2))");
            DebugException e = assertThrows(DebugException.class, () -> top.eval("(set! step 5)"));
            values.add(e.getMessage().contains("step") ? "error names step" : e.getMessage());
            values.add(top.eval("step").toDisplayString());
        });
        assertEquals(List.of("15", "(1 6 #t)", "error names step", "2"), values);
        assertEquals("24", result);
    }

    // The self tail call evaluates its arguments into unnamed temporaries, and or binds a variable
    // of its own: outer's frame has both live while inner runs, and neither shows.
    private static final String TEMPORARIES = """
                    (define (inner y)
                      (* y 2))
                    (define (outer n acc)
                      (if (= n 0)
                          acc
                          (outer (- n 1) (+ acc (or (memv n '()) (inner n))))))
                    (define (run)
                      (car (map (lambda (k) (+ 1 (outer k 0))) '(2))))
                    """;

    @Test
    public void compilerTemporariesAreHiddenAndEveryFrameHasAName() {
        List<String> seen = new ArrayList<>();
        List<String> names = new ArrayList<>();
        String result = runWithBreakpoint(TEMPORARIES, "temps.scm", 2, "(run)", event -> {
            List<DebugStackFrame> frames = new ArrayList<>();
            event.getStackFrames().forEach(frames::add);
            for (String name : frameNames(event)) {
                assertNotNull("a frame has no name", name);
                assertFalse("a frame has an empty name", name.isEmpty());
                names.add(name);
            }
            seen.add(frames.get(0).getName() + " " + variables(frames.get(0)));
            seen.add(frames.get(1).getName() + " " + variables(frames.get(1)));
            seen.add(frames.get(1).eval("(list n acc)").toDisplayString());
        });
        assertEquals("7", result);
        assertEquals(List.of("inner [y=2]", "outer [n=2, acc=0]", "(2 0)", "inner [y=1]", "outer [n=1, acc=4]", "(1 4)"), seen);
        // The anonymous lambda is named by its location.
        assertTrue(names.toString(), names.contains("<lambda@temps.scm:8:13>"));
        assertTrue(names.toString(), names.contains("run"));
    }

    /**
     * The names of the frames a debugger front end lists, innermost first: those on the Java stack,
     * then the asynchronous stack (frames on the heap, plan.md M6-04; under torture, most of them).
     */
    private static List<String> frameNames(SuspendedEvent event) {
        List<String> names = new ArrayList<>();
        for (DebugStackFrame frame : event.getStackFrames()) {
            names.add(frame.getName());
        }
        // By index: Truffle 25.5's iterator of an empty list of asynchronous stacks throws
        // (UpstreamAsyncStacksTest, docs/upstream.md#empty-asynchronous-stacks).
        List<List<DebugStackTraceElement>> asynchronous = event.getAsynchronousStacks();
        for (int i = 0; i < asynchronous.size(); i++) {
            for (DebugStackTraceElement element : asynchronous.get(i)) {
                names.add(element.getName());
            }
        }
        return names;
    }

    @Test
    public void lambdasAreNamedByTheirBinding() {
        String definitions = """
                        (define point-x (let ((index 0)) (lambda (p) (vector-ref p index))))
                        (define (make-point x) (vector x))
                        (define h #f)
                        (set! h (lambda (p)
                                  (+ 1 (point-x p))))
                        (define (run)
                          (let ((g (lambda (p) (+ 1 (h p)))))
                            (g (make-point 7))))
                        """;
        List<List<String>> stacks = new ArrayList<>();
        Source source = Source.newBuilder(SCHEME, definitions, "names.scm").buildLiteral();
        try (Context context = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).build()) {
            Debugger debugger = Debugger.find(context.getEngine());
            try (DebuggerSession session = debugger.startSession(event -> {
                stacks.add(frameNames(event));
                if (stacks.size() == 1) {
                    event.prepareStepInto(1);
                } else {
                    event.prepareContinue();
                }
            })) {
                context.eval(source);
                session.install(Breakpoint.newBuilder(source.getURI()).lineIs(5).build());
                assertEquals(9, context.eval(SCHEME, "(run)").asInt());
            }
        }
        // At the breakpoint in h (bound by set!), called by g (bound by let, tail-called by run);
        // then, stepping in, point-x (a lambda inside a let, as a record accessor was until M7-04
        // made record procedures primitives: recordsShowTheirFields names those).
        assertTrue(stacks.toString(), stacks.size() >= 2);
        assertEquals(stacks.toString(), List.of("h", "g"), stacks.get(0).subList(0, 2));
        assertEquals(stacks.toString(), "point-x", stacks.get(1).get(0));
    }

    /**
     * A record in a frame shows its fields (plan.md M7-04: records are static objects, whose fields
     * the debugger reads as interop members). (A record procedure's root is named by its binding:
     * RecordsTest.)
     */
    @Test
    public void recordsShowTheirFields() {
        String definitions = """
                        (define-record-type point (make-point x y) point? (x point-x set-point-x!) (y point-y))
                        (define (norm p)
                          (+ (point-x p)
                             (point-y p)))
                        """;
        List<String> fields = new ArrayList<>();
        String result = runWithBreakpoint(definitions, "record.scm", 3, "(let ((p (make-point 3 4))) (set-point-x! p 30) (norm p))", event -> {
            DebugValue p = event.getTopStackFrame().getScope().getDeclaredValue("p");
            assertEquals("point", p.getMetaObject().getMetaSimpleName());
            for (DebugValue field : p.getProperties()) {
                fields.add(field.getName() + "=" + field.toDisplayString());
            }
        });
        assertEquals("34", result);
        assertEquals(List.of("x=30", "y=4"), fields);
    }

    /**
     * Frames past the spill depth are in continuation segments on the heap (plan.md M6-04), which
     * are immutable (AGENTS.md rule 3): their variables are read-only to the debugger, and the
     * frames resumed afterwards see the values they had. A Box is shared state (design.md D2), so an
     * assigned variable stays writable.
     */
    @Test
    public void variablesOfSpilledFramesAreReadOnly() {
        String definitions = """
                        (define (down n)
                          (let ((seen 0))
                            (set! seen n)
                            (if (= n 0)
                                (begin
                                  (car (cons n n))
                                  0)
                                (+ n (down (- n 1))))))
                        """;
        int depth = 200;
        List<String> refused = new ArrayList<>();
        List<Boolean> boxedWritable = new ArrayList<>();
        String result = runWithBreakpoint(definitions, "spilled.scm", 6, "(down " + depth + ")", event -> {
            List<List<DebugStackTraceElement>> asynchronous = event.getAsynchronousStacks();
            assertEquals(1, asynchronous.size());
            assertTrue(asynchronous.get(0).size() > 100);
            for (DebugStackTraceElement element : asynchronous.get(0)) {
                DebugValue n = element.getScope().getDeclaredValue("n");
                if (n == null) {
                    continue;
                }
                assertFalse(n.isWritable());
                assertThrows(DebugException.class, () -> n.set(event.getTopStackFrame().eval("0")));
                refused.add(n.toDisplayString());
                boxedWritable.add(element.getScope().getDeclaredValue("seen").isWritable());
            }
        });
        // Every write was refused: each frame still adds its own n.
        assertEquals(Integer.toString(depth * (depth + 1) / 2), result);
        assertTrue(refused.size() > 100);
        assertTrue(boxedWritable.stream().allMatch(w -> w));
    }

    @Test
    public void aTailCallIsNoForeignReturnValue() {
        String program = """
                        (define (g x) (* x 3))
                        (define (f a)
                          (let ((b (+ a 1)))
                            (g b)))
                        (f 1)
                        """;
        List<String> returned = new ArrayList<>();
        Source source = Source.newBuilder(SCHEME, program, "tail.scm").buildLiteral();
        try (Context context = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).build()) {
            Debugger debugger = Debugger.find(context.getEngine());
            try (DebuggerSession session = debugger.startSession(event -> {
                DebugValue value = event.getReturnValue();
                if (value != null) {
                    returned.add(event.getTopStackFrame().getName() + " " + value.toDisplayString());
                }
                event.prepareStepOver(1);
            })) {
                session.install(Breakpoint.newBuilder(source.getURI()).lineIs(4).build());
                assertEquals(6, context.eval(source).asInt());
            }
        }
        assertFalse(returned.isEmpty());
        for (String r : returned) {
            assertFalse(returned.toString(), r.contains("foreign") || r.contains("TailCall"));
        }
    }
}
