package scheme.test.upstream;

import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;

/**
 * A Bytecode DSL root that yields, with tag instrumentation, for the reproducers of what tools do
 * when a frame is resumed (docs/upstream.md, "Tools take a resumption for an entry").
 */
@GenerateBytecode(languageClass = UpstreamLanguage.class, enableYield = true, enableTagInstrumentation = true)
public abstract class UpstreamTaggedYieldRoot extends RootNode implements BytecodeRootNode {

    protected UpstreamTaggedYieldRoot(UpstreamLanguage language, FrameDescriptor frameDescriptor) {
        super(language, frameDescriptor);
    }

    /** The DSL wants one operation of the language's own; the reproducers need only the built-in ones. */
    @Operation
    public static final class Identity {
        @Specialization
        static Object doObject(Object value) {
            return value;
        }
    }

    /**
     * A root over all of {@code source}, which is one statement: {@code return yield 1}. Called, it
     * yields 1; resumed with a value, it returns that value. The Bytecode DSL tags the root
     * ({@code RootTag}); its call target is made here, so instruments see it load.
     */
    public static UpstreamTaggedYieldRoot create(UpstreamLanguage language, Source source) {
        UpstreamTaggedYieldRoot root = UpstreamTaggedYieldRootGen.create(language, BytecodeConfig.WITH_SOURCE, b -> {
            // The root's section is the one around its Root operation (the Bytecode DSL user guide,
            // "Source information"), which the coverage tool needs.
            b.beginSource(source);
            b.beginSourceSection(0, source.getLength());
            b.beginRoot();
            b.beginTag(StandardTags.StatementTag.class);
            b.beginReturn();
            b.beginYield();
            b.emitLoadConstant(1);
            b.endYield();
            b.endReturn();
            b.endTag(StandardTags.StatementTag.class);
            b.endRoot();
            b.endSourceSection();
            b.endSource();
        }).getNode(0);
        root.getCallTarget();
        return root;
    }
}
