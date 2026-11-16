package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;

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
import com.oracle.truffle.tools.profiler.CPUSampler;
import com.oracle.truffle.tools.profiler.CPUSamplerData;
import com.oracle.truffle.tools.profiler.ProfilerNode;

/**
 * EXPLORATION (docs/interop-and-tools-exploration.md): Truffle's debugger and CPU sampler on Scheme
 * code (design.md D13). The debugger API here is what the DAP server and the Chrome inspector
 * drive; these tests show what they see.
 */
public class DebuggerProfilerTest {

    private static final String SCHEME = "scheme";

    private static final String PROGRAM = """
                    (define (square x)
                      (let ((y (* x x)))
                        y))
                    (define (sum-squares n)
                      (let loop ((i 0) (acc 0))
                        (if (= i n)
                            acc
                            (loop (+ i 1) (+ acc (square i))))))
                    (display (sum-squares 3))
                    """;

    /** What a suspended event shows: the top frame's name and line, its scope, and the stack. */
    private static String describe(SuspendedEvent event) {
        DebugStackFrame top = event.getTopStackFrame();
        StringBuilder sb = new StringBuilder();
        sb.append(top.getName()).append(':').append(top.getSourceSection().getStartLine()).append(' ');
        DebugScope scope = top.getScope();
        List<String> vars = new ArrayList<>();
        while (scope != null) {
            for (DebugValue v : scope.getDeclaredValues()) {
                vars.add(v.getName() + "=" + v.toDisplayString());
            }
            scope = scope.getParent();
        }
        sb.append(vars).append(" stack=");
        List<String> frames = new ArrayList<>();
        for (DebugStackFrame frame : event.getStackFrames()) {
            frames.add(frame.getName());
        }
        sb.append(frames);
        return sb.toString();
    }

    @Test
    public void breakpointsSteppingAndLocals() {
        Source source = Source.newBuilder(SCHEME, PROGRAM, "debug.scm").buildLiteral();
        List<String> seen = new ArrayList<>();
        Deque<String> actions = new ArrayDeque<>(List.of("over", "over", "into", "continue"));
        try (Context context = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).build()) {
            Debugger debugger = Debugger.find(context.getEngine());
            try (DebuggerSession session = debugger.startSession(event -> {
                seen.add(describe(event));
                String action = actions.isEmpty() ? "continue" : actions.pop();
                switch (action) {
                    case "over" -> event.prepareStepOver(1);
                    case "into" -> event.prepareStepInto(1);
                    default -> event.prepareContinue();
                }
            })) {
                // A line breakpoint in square's body, by the source's URI.
                session.install(Breakpoint.newBuilder(source.getURI()).lineIs(3).build());
                context.eval(source);
                assertTrue(session.getBreakpoints().get(0).getHitCount() > 0);
            }
        }
        System.out.println("EXPLORATION debugger events:\n  " + String.join("\n  ", seen));
        // What works: line breakpoints, step over and into, Scheme names for procedures, named
        // locals and parameters (shown as Scheme values), and a stack of Scheme frames. A tail call
        // replaces its caller's frame (sum-squares is gone below loop), as it should.
        assertTrue(seen.toString(), seen.get(0).startsWith("square:2 [x=0] stack=[square, loop, <program>]"));
        assertTrue(seen.toString(), seen.get(1).startsWith("loop:8 [i=0, acc=0, "));
        // What is missing (docs/interop-and-tools-exploration.md): let's lambda is a real closure
        // with no name (M5-05 would remove it), so stepping into it shows a frame named null whose
        // scope has only y (x is a captured variable, in the closure, which DefaultBytecodeScope
        // doesn't see); and the code generator's temporaries show up as localN.
        assertTrue(seen.toString(), seen.get(2).startsWith("null:3 [y=0]"));
        assertTrue(seen.toString(), seen.get(1).contains("local2=#<procedure loop>"));
    }

    @Test
    public void theTopScopeIsTheInteractionEnvironment() {
        try (Context context = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).build()) {
            context.eval(SCHEME, "(define answer 42)");
            Debugger debugger = Debugger.find(context.getEngine());
            List<String> seen = new ArrayList<>();
            try (DebuggerSession session = debugger.startSession(event -> {
                DebugScope global = event.getSession().getTopScope(SCHEME);
                seen.add(global.getDeclaredValue("answer").toDisplayString());
                // Evaluating in a frame: the debugger's watch expressions.
                seen.add(event.getTopStackFrame().eval("(+ answer 1)").toDisplayString());
            })) {
                session.suspendNextExecution();
                context.eval(SCHEME, "(display answer)");
            }
            assertEquals(List.of("42", "43"), seen);
        }
    }

    @Test
    public void theCpuSamplerSeesSchemeProcedures() {
        try (Context context = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).build()) {
            CPUSampler sampler = CPUSampler.find(context.getEngine());
            sampler.setPeriod(1);
            sampler.setCollecting(true);
            context.eval(Source.newBuilder(SCHEME, """
                            (define (fib n) (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))
                            (fib 25)
                            """, "fib.scm").buildLiteral());
            sampler.setCollecting(false);
            List<String> roots = new ArrayList<>();
            for (CPUSamplerData data : sampler.getDataList()) {
                for (Map.Entry<Thread, Collection<ProfilerNode<CPUSampler.Payload>>> thread : data.getThreadData().entrySet()) {
                    collect(thread.getValue(), roots);
                }
            }
            assertTrue(roots.toString(), roots.contains("fib fib.scm:1"));
        }
    }

    private static void collect(Collection<ProfilerNode<CPUSampler.Payload>> nodes, List<String> out) {
        Deque<ProfilerNode<CPUSampler.Payload>> work = new ArrayDeque<>(nodes);
        while (!work.isEmpty()) {
            ProfilerNode<CPUSampler.Payload> node = work.pop();
            if (node.getSourceSection() != null) {
                String entry = node.getRootName() + " " + node.getSourceSection().getSource().getName() + ":" + node.getSourceSection().getStartLine();
                if (!out.contains(entry)) {
                    out.add(entry);
                }
            }
            work.addAll(node.getChildren());
        }
    }
}
