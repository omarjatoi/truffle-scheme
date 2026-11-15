package r7rs.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * M0-01 stub root that returns a constant. Hand-written rather than a Bytecode DSL root because it
 * exists only to prove the build and test wiring; M1-05 replaces it with the Bytecode DSL root.
 */
final class LiteralRootNode extends RootNode {

    private final long value;

    LiteralRootNode(TruffleLanguage<?> language, long value) {
        super(language);
        this.value = value;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return value;
    }
}
