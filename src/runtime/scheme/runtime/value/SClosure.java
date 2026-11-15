package scheme.runtime.value;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.interop.TruffleObject;

/** A procedure: a flat closure (design.md D2) over a Bytecode DSL root. */
public final class SClosure implements TruffleObject {

    public final RootCallTarget callTarget;
    public final Object[] captured;

    public SClosure(RootCallTarget callTarget, Object[] captured) {
        this.callTarget = callTarget;
        this.captured = captured;
    }

    public String getName() {
        return callTarget.getRootNode().getName();
    }
}
