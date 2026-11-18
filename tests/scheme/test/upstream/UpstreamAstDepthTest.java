package scheme.test.upstream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.Test;

import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.debug.DebuggerSession;
import com.oracle.truffle.api.debug.SourceElement;
import com.oracle.truffle.api.debug.SuspendedEvent;

/**
 * Truffle 25.5 bug: {@code sandbox.MaxASTDepth} isn't enforced on a Bytecode DSL root
 * (docs/upstream.md, "MaxASTDepth on Bytecode DSL roots"). The sandbox instrument measures a root's
 * depth by walking its child nodes when the root's {@code RootTag} is first instrumented
 * ({@code SandboxInstrument.checkAstDepth}); a Bytecode DSL root's children are its bytecode node and
 * the tag tree of the tags some instrument has asked for, and the sandbox asks only for
 * {@code RootTag}. So a program's depth counts only when another instrument (here, the debugger
 * stepping into expressions) has asked for its expression tags. Our workaround: none
 * (docs/graalvm-configuration.md); docs/embedding.md doesn't offer the limit.
 *
 * <p>Inverted, like every test in this package: it passes while the bug is there. When a Truffle
 * upgrade fixes the bug, it fails; then remove this test and its entry in docs/upstream.md, and
 * check the limit on Scheme programs (docs/graalvm-configuration.md, "Sandboxing").
 */
public class UpstreamAstDepthTest {

    /** Ten expressions, each nested in the one before: an {@code ExpressionTag} ten deep. */
    private static final String DEEP = "((((((((((x))))))))))";
    private static final int LIMIT = 3;

    private static Context context() {
        return Context.newBuilder(UpstreamLanguage.ID).option("sandbox.MaxASTDepth", String.valueOf(LIMIT)).build();
    }

    @Test
    public void aDeepBytecodeRootRunsUnderASmallerLimit() {
        try (Context context = context()) {
            // Expected: cancelled, "AST depth limit of 3 nodes exceeded" (as in the control case).
            assertEquals(42, context.eval(UpstreamLanguage.ID, DEEP).asInt());
        }
    }

    /** Control: once an instrument asks for the expressions' tags, the same program is refused. */
    @Test
    public void theSameRootIsRefusedOnceItsExpressionsAreInstrumented() {
        Context context = context();
        // A debugger session that steps into expressions materializes their tags.
        DebuggerSession session = Debugger.find(context.getEngine()).startSession((@SuppressWarnings("unused") SuspendedEvent event) -> {
        }, SourceElement.EXPRESSION);
        session.suspendNextExecution();
        PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval(UpstreamLanguage.ID, DEEP));
        assertTrue(e.toString(), e.isResourceExhausted());
        // The root tag, the statement and the ten expressions.
        assertEquals("AST depth limit of 3 nodes exceeded. AST depth: 12.", e.getMessage());
        // A context cancelled by a limit throws the cancellation again when it is closed.
        assertThrows(PolyglotException.class, context::close);
    }
}
