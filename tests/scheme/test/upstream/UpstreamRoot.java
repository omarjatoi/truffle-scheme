package scheme.test.upstream;

import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.nodes.RootNode;

/** A small Bytecode DSL root for reproducing Truffle bugs (docs/upstream.md). */
@GenerateBytecode(languageClass = UpstreamLanguage.class, enableTagInstrumentation = true)
public abstract class UpstreamRoot extends RootNode implements BytecodeRootNode {

    protected UpstreamRoot(UpstreamLanguage language, FrameDescriptor frameDescriptor) {
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
}
