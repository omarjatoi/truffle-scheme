package scheme.test.upstream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.graalvm.polyglot.Context;
import org.junit.Test;

import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.bytecode.ContinuationRootNode;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;

/**
 * Truffle 25.5 bug: the continuation root of a Bytecode DSL root that yields
 * ({@code ContinuationRootNode}, which runs the root's frame when it is resumed) isn't internal
 * when the root is, and has no source section (docs/upstream.md, "Continuation roots are never
 * internal"). Stack traces ask the frame's root whether it is internal, so a resumed frame of
 * internal code shows. Our workaround: {@code SchemeRootNode.translateStackTraceElement} and
 * {@code SchemeError}'s location ask the continuation's source root instead (scheme.runtime).
 *
 * <p>Inverted, like every test in this package: it passes while the bug is there. When a Truffle
 * upgrade fixes the bug, it fails; then remove the workaround, this test and its entry in
 * docs/upstream.md.
 */
public class UpstreamContinuationRootTest {

    private static final LanguageReference<UpstreamLanguage> LANGUAGE = LanguageReference.create(UpstreamLanguage.class);

    @Test
    public void aContinuationOfAnInternalRootIsNotInternal() {
        try (Context context = Context.create(UpstreamLanguage.ID)) {
            context.initialize(UpstreamLanguage.ID);
            context.enter();
            try {
                Source internal = Source.newBuilder(UpstreamLanguage.ID, "yield", "internal.txt").internal(true).build();
                UpstreamYieldRoot root = UpstreamYieldRootGen.create(LANGUAGE.get(null), BytecodeConfig.WITH_SOURCE, b -> {
                    b.beginRoot();
                    b.beginSource(internal);
                    b.beginSourceSection(0, internal.getLength());
                    b.beginReturn();
                    b.beginYield();
                    b.emitLoadConstant(1);
                    b.endYield();
                    b.endReturn();
                    b.endSourceSection();
                    b.endSource();
                    b.endRoot();
                }).getNode(0);
                assertTrue("the root is internal", root.isInternal());

                Object result = root.getCallTarget().call();
                ContinuationResult yielded = (ContinuationResult) result;
                assertEquals(1, yielded.getResult());
                ContinuationRootNode continuation = yielded.getContinuationRootNode();
                assertTrue(((RootNode) continuation.getSourceRootNode()).isInternal());
                // Expected: true, as its source root's. Actual: false, as it has no source section.
                assertFalse(continuation.isInternal());
                assertNull(continuation.getSourceSection());
                // Resumed, it runs the root's code all the same.
                assertEquals(2, yielded.continueWith(2));
            } finally {
                context.leave();
            }
        }
    }
}
