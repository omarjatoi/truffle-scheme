package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.Test;

import com.oracle.truffle.api.debug.Breakpoint;
import com.oracle.truffle.api.debug.DebugScope;
import com.oracle.truffle.api.debug.DebugStackFrame;
import com.oracle.truffle.api.debug.DebugValue;
import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.debug.DebuggerSession;
import com.oracle.truffle.api.debug.SuspendedEvent;

/**
 * Truffle's debugger on Scheme code (design.md D13), through the API the DAP server and the Chrome
 * inspector drive: a session attaches (every session installs an {@code AlwaysHalt} breakpoint, so
 * the language must provide that tag), line breakpoints suspend, stepping works, and parameters
 * show by name.
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
}
