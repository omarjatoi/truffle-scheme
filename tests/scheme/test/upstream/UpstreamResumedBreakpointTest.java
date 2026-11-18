package scheme.test.upstream;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.junit.Test;

import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.debug.Breakpoint;
import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.debug.DebuggerSession;
import com.oracle.truffle.api.debug.SuspendAnchor;
import com.oracle.truffle.api.source.Source;

/**
 * Truffle 25.5 bug: a line breakpoint is hit again when the frame that stopped at it yields and is
 * resumed (docs/upstream.md, "A breakpoint is hit again when its frame resumes"). Its node
 * overrides {@code onEnter} only, and {@code ExecutionEventNode.onResume} calls {@code onEnter}.
 * In Scheme, a statement with a breakpoint whose call spills (design.md "Stack-depth spill") stops
 * twice, once before the call and once when its frame comes back from the heap; there's no
 * workaround. docs/usage.md's tools table says so.
 *
 * <p>Inverted, like every test in this package: it passes while the bug is there. When a Truffle
 * upgrade fixes the bug, it fails; then remove this test, its entry in docs/upstream.md, and the
 * limitation in docs/usage.md and design.md D13 ("Yields and tools").
 */
public class UpstreamResumedBreakpointTest {

    private static final LanguageReference<UpstreamLanguage> LANGUAGE = LanguageReference.create(UpstreamLanguage.class);

    @Test
    public void aBreakpointIsHitAgainWhenItsFrameResumes() {
        Source source = Source.newBuilder(UpstreamLanguage.ID, "return yield 1", "resumed.txt").build();
        List<String> stops = new ArrayList<>();
        try (Context context = Context.create(UpstreamLanguage.ID)) {
            context.initialize(UpstreamLanguage.ID);
            try (DebuggerSession session = Debugger.find(context.getEngine()).startSession(event -> {
                stops.add(event.getSuspendAnchor() + " " + event.getSourceSection().getCharacters());
            })) {
                Breakpoint breakpoint = Breakpoint.newBuilder(source).lineIs(1).build();
                session.install(breakpoint);
                context.enter();
                try {
                    UpstreamTaggedYieldRoot root = UpstreamTaggedYieldRoot.create(LANGUAGE.get(null), source);
                    ContinuationResult yielded = (ContinuationResult) root.getCallTarget().call();
                    assertEquals(1, yielded.getResult());
                    assertEquals(1, breakpoint.getHitCount());
                    // Resumed in place, the frame goes on from the yield inside the statement: it
                    // doesn't enter the statement again.
                    assertEquals(2, yielded.continueWith(2));
                    // Expected: still 1. Actual: 2, a second stop before the statement.
                    assertEquals(2, breakpoint.getHitCount());
                } finally {
                    context.leave();
                }
            }
        }
        assertEquals(List.of(SuspendAnchor.BEFORE + " return yield 1", SuspendAnchor.BEFORE + " return yield 1"), stops);
    }
}
