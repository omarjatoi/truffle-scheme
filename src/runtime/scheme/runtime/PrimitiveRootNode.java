package scheme.runtime;

import java.util.Arrays;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * The root of a primitive procedure. Hand-written rather than a Bytecode DSL root: it only checks the
 * arity and delegates to a {@link PrimitiveNode}, which has no bytecode to interpret.
 */
public final class PrimitiveRootNode extends RootNode {

    private final PrimitiveSpec spec;
    @Child private PrimitiveNode body;

    public PrimitiveRootNode(SchemeLanguage language, PrimitiveSpec spec) {
        super(language);
        this.spec = spec;
        this.body = spec.factory().get();
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] all = frame.getArguments();
        int count = all.length - 1;
        if (count < spec.minArgs() || (spec.maxArgs() >= 0 && count > spec.maxArgs())) {
            throw arity(count);
        }
        return body.execute(Arrays.copyOfRange(all, 1, all.length));
    }

    @TruffleBoundary
    private SchemeError arity(int count) {
        String expected = spec.maxArgs() < 0 ? "at least " + spec.minArgs() : spec.minArgs() == spec.maxArgs() ? Integer.toString(spec.minArgs()) : spec.minArgs() + " to " + spec.maxArgs();
        return new SchemeError(spec.name() + ": expected " + expected + " arguments, got " + count);
    }

    @Override
    public String getName() {
        return spec.name();
    }

    @Override
    public String toString() {
        return spec.name();
    }
}
