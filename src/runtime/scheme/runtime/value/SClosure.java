package scheme.runtime.value;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.interop.TruffleObject;

/** A procedure: a flat closure (design.md D2) over a Bytecode DSL root. */
public final class SClosure implements TruffleObject {

    public final RootCallTarget callTarget;

    /**
     * The captured values, never written once the closure exists: a variable that {@code set!}
     * assigns is captured as its {@code Box} (design.md D2). So when the closure is a compilation
     * constant (a global procedure under its cell's assumption, a closure inlined with the call that
     * made it), reading a captured value folds to a constant too (plan.md M5-10). The one write
     * after construction, {@code PatchCaptured} (Fixing Letrec, M5-05), happens before anything can
     * read the slot.
     */
    @CompilationFinal(dimensions = 1) public final Object[] captured;

    public SClosure(RootCallTarget callTarget, Object[] captured) {
        this.callTarget = callTarget;
        this.captured = captured;
    }

    public String getName() {
        return callTarget.getRootNode().getName();
    }
}
