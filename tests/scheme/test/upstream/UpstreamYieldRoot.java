package scheme.test.upstream;

import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.nodes.RootNode;

/** A Bytecode DSL root that can yield, for UpstreamContinuationRootTest (docs/upstream.md). */
@GenerateBytecode(languageClass = UpstreamLanguage.class, enableYield = true)
public abstract class UpstreamYieldRoot extends RootNode implements BytecodeRootNode {

    protected UpstreamYieldRoot(UpstreamLanguage language, FrameDescriptor frameDescriptor) {
        super(language, frameDescriptor);
    }

    /** The DSL wants one operation of the language's own; the reproducer needs only the built-in ones. */
    @Operation
    public static final class Identity {
        @Specialization
        static Object doObject(Object value) {
            return value;
        }
    }
}
