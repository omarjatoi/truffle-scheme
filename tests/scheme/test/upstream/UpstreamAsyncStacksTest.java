package scheme.test.upstream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.junit.Test;

import com.oracle.truffle.api.debug.DebugStackTraceElement;
import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.debug.DebuggerSession;

/**
 * Truffle 25.5 bug: when a suspended program has no asynchronous stack, the list
 * {@code SuspendedEvent.getAsynchronousStacks()} returns says it is empty, but iterating it throws
 * {@code IndexOutOfBoundsException} (docs/upstream.md, "Iterating an empty getAsynchronousStacks()
 * throws"). Our workaround: DebuggerTest.frameNames and SpilledFramesTest.describe loop by index, or
 * don't ask.
 *
 * <p>Inverted, like every test in this package: it passes while the bug is there. When a Truffle
 * upgrade fixes the bug, it fails; then remove the workarounds, this test and its entry in
 * docs/upstream.md.
 */
public class UpstreamAsyncStacksTest {

    @Test
    public void iteratingAnEmptyListOfAsynchronousStacksThrows() {
        List<Integer> sizes = new ArrayList<>();
        List<Throwable> thrown = new ArrayList<>();
        try (Context context = Context.create(UpstreamLanguage.ID)) {
            Debugger debugger = Debugger.find(context.getEngine());
            try (DebuggerSession session = debugger.startSession(event -> {
                List<List<DebugStackTraceElement>> stacks = event.getAsynchronousStacks();
                sizes.add(stacks.size());
                try {
                    for (List<DebugStackTraceElement> stack : stacks) {
                        sizes.add(stack.size());
                    }
                } catch (RuntimeException e) {
                    thrown.add(e);
                }
            })) {
                session.suspendNextExecution();
                assertEquals(42, context.eval(UpstreamLanguage.ID, "x").asInt());
            }
        }
        // One suspension, with no asynchronous stack: size() is 0 ...
        assertEquals(List.of(0), sizes);
        // ... and the for loop should run zero times, but its first hasNext() throws
        // (DebugAsyncStackFrameLists.fillStacks reads stacks.get(-1)).
        assertEquals(1, thrown.size());
        assertTrue(thrown.get(0).toString(), thrown.get(0) instanceof IndexOutOfBoundsException);
        assertNotNull(thrown.get(0).getMessage());
        assertTrue(thrown.get(0).getMessage(), thrown.get(0).getMessage().contains("-1"));
    }
}
