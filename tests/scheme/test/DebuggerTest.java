package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.oracle.truffle.api.debug.Breakpoint;
import com.oracle.truffle.api.debug.DebugScope;
import com.oracle.truffle.api.debug.DebugStackFrame;
import com.oracle.truffle.api.debug.DebugStackTraceElement;
import com.oracle.truffle.api.debug.DebugValue;
import com.oracle.truffle.api.debug.DebugException;
import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.debug.DebuggerSession;
import com.oracle.truffle.api.debug.SuspendAnchor;
import com.oracle.truffle.api.debug.SuspendedEvent;
import com.oracle.truffle.api.debug.SuspensionFilter;
import com.oracle.truffle.api.source.SourceSection;

import scheme.runtime.SchemeLanguage;

/**
 * Truffle's debugger on Scheme code (design.md D13), through the API the DAP server and the Chrome
 * inspector drive: a session attaches (every session installs an {@code AlwaysHalt} breakpoint, so
 * the language must provide that tag), line breakpoints suspend, stepping works, and parameters
 * show by name. A frame's scope shows the variables that Scheme code there can refer to (plan.md
 * M6-05): its own and the captured ones, boxes unboxed, no compiler temporaries; expressions
 * evaluate in a suspended frame; and every frame has a name.
 */
public class DebuggerTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

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
        // Spills past 50 nested calls, however much Java stack there is (plan.md M8-01), so that
        // variablesOfSpilledFramesAreReadOnly has frames on the heap.
        try (Context context = Context.newBuilder(SCHEME).allowExperimentalOptions(true).option("scheme.SpillDepth", "50").out(new ByteArrayOutputStream()).build()) {
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

    // An isolated program (design.md D15): it imports only (scheme base), and defines base and
    // scale. scale's body is line 5.
    private static final String ISOLATED = """
                    (import (scheme base))
                    (define base 10)
                    (define table (list 1 2))
                    (define (scale x)
                      (* x base))
                    (scale 2)
                    """;

    /**
     * M7-07: code evaluated in a frame of an isolated program sees that program's top level (its
     * definitions and imports), not the interaction environment's; and it may not assign the
     * program's variables, which the program was compiled on the assumption that nothing else does.
     */
    @Test
    public void expressionsInAnIsolatedProgramsFrameSeeItsDefinitions() {
        Source source = Source.newBuilder(SCHEME, ISOLATED, "isolated.scm").buildLiteral();
        List<String> values = new ArrayList<>();
        try (Context context = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).build()) {
            // Defined in the interaction environment only: the program can't see it.
            context.eval(SCHEME, "(define only-interactive 1)");
            Debugger debugger = Debugger.find(context.getEngine());
            try (DebuggerSession session = debugger.startSession(event -> {
                DebugStackFrame top = event.getTopStackFrame();
                values.add(top.getName());
                values.add(top.eval("(list x base (procedure? scale) table)").toDisplayString());
                for (String name : new String[]{"only-interactive", "display"}) {
                    DebugException e = assertThrows(DebugException.class, () -> top.eval(name));
                    values.add(e.getMessage().contains(name) ? name + " is unbound" : e.getMessage());
                }
                DebugException e = assertThrows(DebugException.class, () -> top.eval("(set! base 0)"));
                values.add(e.getMessage().contains("base") ? "set! names base" : e.getMessage());
                event.prepareContinue();
            })) {
                session.install(Breakpoint.newBuilder(source.getURI()).lineIs(5).build());
                assertEquals(20, context.eval(source).asInt());
            }
        }
        assertEquals(List.of("scale", "(2 10 #t (1 2))", "only-interactive is unbound", "display is unbound", "set! names base"), values);
    }

    // A library on the library path: it imports only (scheme base), defines factor without
    // exporting it, and exports scaled, whose body is line 7.
    private static final String LIBRARY = """
                    (define-library (debugged lib)
                      (export scaled)
                      (import (scheme base))
                      (begin
                        (define factor 7)
                        (define (scaled x)
                          (* x factor))))
                    """;

    /**
     * Code evaluated in a frame of a library's procedure sees that library's top level (its
     * definitions, exported or not, and its imports), not the interaction environment's that
     * imported it. Unlike a program's, a library's variables may be assigned from another unit (by
     * a macro it exports), so the debugger may assign them too.
     */
    @Test
    public void expressionsInALibrarysFrameSeeItsDefinitions() throws IOException {
        Path dir = folder.newFolder("lib").toPath();
        Path file = Files.createDirectories(dir.resolve("debugged")).resolve("lib.sld");
        Files.writeString(file, LIBRARY);
        List<String> values = new ArrayList<>();
        try (Context context = Context.newBuilder(SCHEME).allowIO(IOAccess.ALL).option("scheme.LibraryPath", dir.toString()).out(new ByteArrayOutputStream()).build()) {
            // Defined in the interaction environment only: the library can't see it.
            context.eval(SCHEME, "(define only-interactive 1)");
            Debugger debugger = Debugger.find(context.getEngine());
            try (DebuggerSession session = debugger.startSession(event -> {
                DebugStackFrame top = event.getTopStackFrame();
                values.add(top.getName());
                values.add(top.eval("(list x factor (procedure? scaled))").toDisplayString());
                for (String name : new String[]{"only-interactive", "display"}) {
                    DebugException e = assertThrows(DebugException.class, () -> top.eval(name));
                    values.add(e.getMessage().contains(name) ? name + " is unbound" : e.getMessage());
                }
                top.eval("(set! factor 8)");
                event.prepareContinue();
            })) {
                Breakpoint breakpoint = Breakpoint.newBuilder(file.toUri()).lineIs(7).build();
                session.install(breakpoint);
                assertEquals(24, context.eval(SCHEME, "(import (debugged lib)) (scaled 3)").asInt());
                assertEquals(1, breakpoint.getHitCount());
            }
        }
        assertEquals(List.of("scaled", "(3 7 #t)", "only-interactive is unbound", "display is unbound"), values);
    }

    // Called by map with two lists; its body, line 2, calls only primitives (see PROGRAM).
    private static final String MAPPED = """
                    (define (pair-up x y)
                      (car (cons x y)))
                    """;

    /**
     * A standard library's procedure, restored from the prelude image (design.md D13), evaluates in
     * its library's top level too: in map's frame, (r7rs %prelude)'s definitions that no library
     * exports are bound, and the interaction environment's aren't. (The prelude's sources are
     * internal, so the session includes internal frames, as a front end stepping into them would.)
     */
    @Test
    public void expressionsInAStandardLibrarysFrameSeeItsDefinitions() {
        List<String> values = new ArrayList<>();
        try (Context context = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).build()) {
            Source source = Source.newBuilder(SCHEME, MAPPED, "mapped.scm").buildLiteral();
            context.eval(source);
            // Defined in the interaction environment only: the prelude can't see it.
            context.eval(SCHEME, "(define only-interactive 1)");
            Debugger debugger = Debugger.find(context.getEngine());
            try (DebuggerSession session = debugger.startSession(event -> {
                DebugStackFrame map = null;
                for (DebugStackFrame frame : event.getStackFrames()) {
                    if (frame.getSourceSection() != null && frame.getSourceSection().getSource().getName().equals("prelude/lists-vectors.scm")) {
                        map = frame;
                        break;
                    }
                }
                assertNotNull("no frame of map", map);
                DebugStackFrame frame = map;
                values.add(frame.getName());
                // %cars and %all-pairs? are (r7rs %prelude)'s own: neither it nor (scheme base) exports them.
                values.add(frame.eval("(list (%cars ls) (%all-pairs? ls) (procedure? %vector-refs))").toDisplayString());
                DebugException e = assertThrows(DebugException.class, () -> frame.eval("only-interactive"));
                values.add(e.getMessage().contains("only-interactive") ? "only-interactive is unbound" : e.getMessage());
                event.prepareContinue();
            })) {
                session.setSteppingFilter(SuspensionFilter.newBuilder().includeInternal(true).build());
                session.install(Breakpoint.newBuilder(source.getURI()).lineIs(2).build());
                assertEquals("(1 2)", context.eval(SCHEME, "(map pair-up '(1 2) '(3 4))").toString());
            }
        }
        assertTrue(values.toString(), values.size() >= 6);
        assertEquals(List.of("map", "((1 3) #t #t)", "only-interactive is unbound", "map", "((2 4) #t #t)", "only-interactive is unbound"), values.subList(0, 6));
    }

    // Called from code evaluated in an environment; its body, line 2, calls only primitives.
    private static final String CALLBACK = """
                    (define (note y)
                      (car (cons y y)))
                    """;

    /**
     * Code that eval compiles into an environment (R7RS §6.12) evaluates in that environment's top
     * level: it sees the environment's imports and not the interaction environment's definitions,
     * and may not assign them (an environment from environment is immutable). In each environment,
     * a lambda calls note, which has the breakpoint; the lambda's frame is the one below.
     */
    @Test
    public void expressionsInEvaluatedCodeSeeItsEnvironment() {
        List<String> values = new ArrayList<>();
        try (Context context = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).build()) {
            Source source = Source.newBuilder(SCHEME, CALLBACK, "callback.scm").buildLiteral();
            context.eval(source);
            context.eval(SCHEME, "(define only-interactive 1)");
            Debugger debugger = Debugger.find(context.getEngine());
            try (DebuggerSession session = debugger.startSession(event -> {
                List<DebugStackFrame> frames = new ArrayList<>();
                event.getStackFrames().forEach(frames::add);
                DebugStackFrame frame = frames.get(1);
                values.add(frame.eval("(let ((z y)) (if z (cons x z) #f))").toDisplayString());
                for (String name : new String[]{"only-interactive", "list"}) {
                    try {
                        frame.eval(name);
                        values.add(name + " is bound");
                    } catch (DebugException e) {
                        values.add(e.getMessage().contains(name) ? name + " is unbound" : e.getMessage());
                    }
                }
                DebugException e = assertThrows(DebugException.class, () -> frame.eval("(set! car car)"));
                values.add(e.getMessage().contains("set!") ? "set! refused" : e.getMessage());
                event.prepareContinue();
            })) {
                session.install(Breakpoint.newBuilder(source.getURI()).lineIs(2).build());
                for (String environment : new String[]{"(environment '(scheme base))", "(scheme-report-environment 5)", "(environment '(only (scheme base) cons car if let lambda * set!))"}) {
                    String program = "((eval '(lambda (f x) (let ((y (* x 2))) (if (f y) y #f))) " + environment + ") note 5)";
                    assertEquals("10", context.eval(SCHEME, program).toString());
                }
            }
        }
        assertEquals(List.of(
                        "(5 . 10)", "only-interactive is unbound", "list is bound", "set! refused",
                        "(5 . 10)", "only-interactive is unbound", "list is bound", "set! refused",
                        "(5 . 10)", "only-interactive is unbound", "list is unbound", "set! refused"), values.subList(0, Math.min(values.size(), 12)));
    }

    // A file loaded into an environment: its value (load's, here) is a procedure, whose body, line
    // 2, calls only primitives.
    private static final String LOADED = """
                    (lambda (x)
                      (car (cons x x)))
                    """;

    /** Code that load compiles into an environment evaluates in that environment's top level, as eval's does. */
    @Test
    public void expressionsInLoadedCodeSeeItsEnvironment() throws IOException {
        Path file = folder.newFile("loaded.scm").toPath();
        Files.writeString(file, LOADED);
        List<String> values = new ArrayList<>();
        try (Context context = Context.newBuilder(SCHEME).allowIO(IOAccess.ALL).out(new ByteArrayOutputStream()).build()) {
            context.eval(SCHEME, "(define only-interactive 1)");
            Debugger debugger = Debugger.find(context.getEngine());
            try (DebuggerSession session = debugger.startSession(event -> {
                DebugStackFrame top = event.getTopStackFrame();
                values.add(top.eval("(cons x (car '(1)))").toDisplayString());
                DebugException e = assertThrows(DebugException.class, () -> top.eval("only-interactive"));
                values.add(e.getMessage().contains("only-interactive") ? "only-interactive is unbound" : e.getMessage());
                e = assertThrows(DebugException.class, () -> top.eval("(set! car cdr)"));
                values.add(e.getMessage().contains("set!") ? "set! refused" : e.getMessage());
                event.prepareContinue();
            })) {
                Breakpoint breakpoint = Breakpoint.newBuilder(file.toUri()).lineIs(2).build();
                session.install(breakpoint);
                String path = file.toString().replace("\\", "\\\\").replace("\"", "\\\"");
                assertEquals(21, context.eval(SCHEME, "((load \"" + path + "\" (environment '(scheme base))) 21)").asInt());
                assertEquals(1, breakpoint.getHitCount());
            }
        }
        assertEquals(List.of("(21 . 1)", "only-interactive is unbound", "set! refused"), values);
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

    /** {@code name:line} of each stop, with {@code =value} after a call, and the stack's depth. */
    private static String stop(SuspendedEvent event) {
        if (event.getException() != null) {
            return "exception: " + event.getException().getMessage();
        }
        SourceSection section = event.getSourceSection();
        StringBuilder sb = new StringBuilder(event.getTopStackFrame().getName()).append(':').append(section == null ? "?" : Integer.toString(section.getStartLine()));
        if (event.getSuspendAnchor() == SuspendAnchor.AFTER) {
            sb.append('=').append(event.getReturnValue() == null ? "?" : event.getReturnValue().toDisplayString());
        }
        int depth = 0;
        for (@SuppressWarnings("unused")
        DebugStackFrame frame : event.getStackFrames()) {
            depth++;
        }
        return sb.append(" @").append(depth).toString();
    }

    /**
     * Runs {@code program} with a breakpoint on {@code line} (none if 0), and with an exception
     * breakpoint (caught and uncaught, a front end's "pause on exceptions") if {@code exceptions};
     * at each stop, the next of {@code steps} ({@code i} into, {@code o} over, {@code u} out),
     * then continue. Returns the stops, then the program's value. With {@code located}, the
     * stepping filter the DAP server and the Chrome inspector set: stop only where there is a
     * source location to show.
     */
    private static List<String> steps(String program, int line, String steps, boolean located, boolean exceptions) {
        // Not under torture, as SteppingTest: there every non-tail call suspends its frame and the
        // root prompt resumes it, which Truffle's debugger takes for a generator's suspension and a
        // new call (design.md D13, "Yields and tools").
        Assume.assumeFalse("torture mode", SchemeLanguage.constant(SchemeLanguage.TORTURE) != SchemeLanguage.Torture.OFF);
        Source source = Source.newBuilder(SCHEME, program, "steps.scm").buildLiteral();
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
                if (line > 0) {
                    session.install(Breakpoint.newBuilder(source.getURI()).lineIs(line).build());
                }
                if (exceptions) {
                    session.install(Breakpoint.newExceptionBuilder(true, true).build());
                }
                stops.add(context.eval(source).toString());
            }
        }
        return stops;
    }

    /**
     * Each k is invoked in a non-tail call in its receiver's body, in a procedure the receiver
     * calls, or in a handler of an error an operation raised there, so the frames between it and
     * call/cc's prompt are abandoned (design.md D13, "Ending an escaped frame for tools").
     */
    private static final String ESCAPES = """
                    (import (scheme base))
                    (define (escape)
                      (let ((r (call/cc (lambda (k)
                                          (k 5)
                                          0))))
                        (+ r 1)))
                    (define (g k)
                      (k 6)
                      0)
                    (define (nested)
                      (let ((r (call/cc (lambda (k) (+ 1 (g k))))))
                        (+ r 1)))
                    (define saved #f)
                    (define (full)
                      (let ((r (call/cc (lambda (k)
                                          (set! saved k)
                                          (k 7)
                                          0))))
                        (+ r 1)))
                    (define (handled)
                      (let ((r (call/cc (lambda (k)
                                          (with-exception-handler
                                            (lambda (e) (k 9))
                                            (lambda ()
                                              (+ 1 (car 5))))))))
                        (+ r 1)))
                    (list (escape) (nested) (full) (handled))
                    """;

    private static final String VALUE = "(6 7 8 10)";

    /** {@link #steps} with and without the stepping filter, which must stop at the same places. */
    private static void assertSteps(List<String> expected, int line, String steps) {
        assertEquals(expected, steps(ESCAPES, line, steps, false, false));
        assertEquals(expected, steps(ESCAPES, line, steps, true, false));
    }

    @Test
    public void aStepOverAnEscapeStopsAtTheNextStatementAfterThePrompt() {
        // An escape-only k (ESCAPE): the receiver's frame ends, and the step stops at the statement
        // after call/cc, in the frame that called it.
        assertSteps(List.of("<lambda@steps.scm:3:21>:4 @5", "escape:6 @3", VALUE), 4, "o");
        // From a procedure the receiver called: both frames end. A step out of it goes there too.
        assertSteps(List.of("g:8 @6", "nested:12 @3", VALUE), 8, "o");
        assertSteps(List.of("g:8 @6", "nested:12 @3", VALUE), 8, "u");
    }

    @Test
    public void aStepOverAFullContinuationsInvocationStopsWhereItResumes() {
        // A full continuation (APPLY): the frames below the root prompt end, and the prompt resumes
        // the continuation's, in the middle of full's let, whose statement the step stops at first.
        assertSteps(List.of("<lambda@steps.scm:15:21>:17 @2", "full:15 @2", "full:19 @2", VALUE), 17, "oo");
        // From the handler of an error that car raised (k is a full continuation there, since the
        // handler keeps it): the frame car raised it in ends, without the call site's check.
        assertSteps(List.of("<lambda@steps.scm:24:25>:25 @3", "handled:21 @2", "handled:26 @2", VALUE), 25, "oo");
    }

    /**
     * Each raise is caught by a guard, which captures the frames between the raise and itself as a
     * composable continuation and drops it (design.md D4): the raise's own frame, the frames that
     * called it (thrower's caller), and in deep, 10,000 of them, most of them spilled to the heap.
     */
    private static final String GUARDS = """
                    (import (scheme base))
                    (define (caught)
                      (let ((r (guard (e ((symbol? e) 2))
                                 (car '(1))
                                 (raise 'oops)
                                 0)))
                        (+ r 1)))
                    (define (thrower)
                      (car '(1))
                      (raise 'deep)
                      0)
                    (define (nested)
                      (let ((r (guard (e (#t 4))
                                 (+ 1 (thrower)))))
                        (+ r 1)))
                    (define (bottom)
                      (car '(1))
                      (raise 'bottom)
                      0)
                    (define (down n)
                      (if (= n 0) (bottom) (+ 1 (down (- n 1)))))
                    (define (deep)
                      (guard (e (#t 6))
                        (down 10000)))
                    (list (caught) (nested) (deep))
                    """;

    private static final String GUARDS_VALUE = "(3 5 6)";

    private static void assertGuardSteps(List<String> expected, int line, String steps) {
        assertEquals(expected, steps(GUARDS, line, steps, false, false));
        assertEquals(expected, steps(GUARDS, line, steps, true, false));
    }

    @Test
    public void aStepOverARaiseThatAGuardCatchesStopsAtTheNextStatement() {
        // The frames the guard's capture suspended end for tools (design.md D13, "Ending a guard's
        // frames for tools"), and the step stops at the next statement that runs: the guard's
        // clauses, which are located at the guard, in the procedure the guard makes of them.
        assertGuardSteps(List.of("<lambda@steps.scm:3:12>:4 @8", "<lambda@steps.scm:3:12>:5 @8", "<lambda@steps.scm:3:12>:3 @4", GUARDS_VALUE), 4, "oo");
        // From a procedure the guard's body called: its frame ends, then its caller's, which
        // doesn't stop on the way.
        assertGuardSteps(List.of("thrower:9 @9", "thrower:10 @9", "<lambda@steps.scm:13:12>:13 @4", GUARDS_VALUE), 9, "oo");
        // 10,000 frames down, most of them on the heap.
        List<String> deep = steps(GUARDS, 17, "oo", false, false);
        assertEquals(deep.toString(), 4, deep.size());
        assertTrue(deep.toString(), deep.get(0).startsWith("bottom:17 @") && deep.get(1).startsWith("bottom:18 @"));
        assertEquals(List.of("<lambda@steps.scm:23:3>:23 @2", GUARDS_VALUE), deep.subList(2, 4));
    }

    @Test
    public void aBreakpointOnACaughtRaiseIsHitAgainAsItsFrameEnds() {
        // Known limitation (docs/upstream.md, "A breakpoint is hit again when its frame resumes"):
        // the frame ends by resuming, inside the statement that has the breakpoint.
        assertGuardSteps(List.of("<lambda@steps.scm:3:12>:5 @8", "<lambda@steps.scm:3:12>:5 @8", GUARDS_VALUE), 5, "o");
    }

    @Test
    public void anExceptionBreakpointDoesntStopAtAnEscape() {
        // An escape ends its frames with a control-flow exception, which is no guest exception
        // (design.md D13, "Ending an escaped frame for tools"). car's error is raised in its frame,
        // to the handler, so it leaves no tag either.
        assertEquals(List.of(VALUE), steps(ESCAPES, 0, "", false, true));
    }
}
