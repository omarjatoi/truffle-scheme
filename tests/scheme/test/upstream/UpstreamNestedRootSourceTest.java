package scheme.test.upstream;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

/**
 * Truffle 25.5 bug: a Bytecode DSL root built while another root is being built gets its source
 * sections in the outermost open {@code Source}, not in the one it opened itself (docs/upstream.md,
 * "Nested roots' source sections are in the wrong Source"). Our workaround:
 * {@code CodeGenerator.usable} (scheme.compiler).
 *
 * <p>Inverted, like every test in this package: it passes while the bug is there. When a Truffle
 * upgrade fixes the bug, it fails; then remove the workaround, this test and its entry in
 * docs/upstream.md.
 */
public class UpstreamNestedRootSourceTest {

    private static final Source OUTER = Source.newBuilder(UpstreamLanguage.ID, "outer source text", "outer.txt").build();
    private static final Source INNER = Source.newBuilder(UpstreamLanguage.ID, "INNER", "inner.txt").build();

    /** Builds a root whose only code is in section [0, 5) of INNER: alone, or nested in another root. */
    private static UpstreamRoot buildInner(boolean nested) {
        UpstreamRoot[] inner = new UpstreamRoot[1];
        BytecodeRootNodes<UpstreamRoot> nodes = UpstreamRootGen.create(null, BytecodeConfig.WITH_SOURCE, b -> {
            if (nested) {
                b.beginRoot();
                b.beginSource(OUTER);
                b.beginSourceSection(0, OUTER.getLength());
                b.beginBlock();
            }

            b.beginRoot();
            b.beginSource(INNER);
            b.beginSourceSection(0, 5);
            b.beginReturn();
            b.emitLoadConstant(1);
            b.endReturn();
            b.endSourceSection();
            b.endSource();
            inner[0] = b.endRoot();

            if (nested) {
                b.beginReturn();
                b.emitLoadConstant(0);
                b.endReturn();
                b.endBlock();
                b.endSourceSection();
                b.endSource();
                b.endRoot();
            }
        });
        assertEquals(nested ? 2 : 1, nodes.count());
        return inner[0];
    }

    private static String location(UpstreamRoot root) {
        SourceSection section = root.getBytecodeNode().getSourceLocation(0);
        return section.getSource().getName() + " [" + section.getCharIndex() + ", " + section.getCharEndIndex() + ") " + section.getCharacters();
    }

    @Test
    public void aRootBuiltAloneHasItsSectionInItsOwnSource() {
        assertEquals("inner.txt [0, 5) INNER", location(buildInner(false)));
    }

    @Test
    public void aNestedRootHasItsSectionInTheOuterRootsSource() {
        // Expected (and what a root built alone gets): "inner.txt [0, 5) INNER".
        assertEquals("outer.txt [0, 5) outer", location(buildInner(true)));
    }
}
