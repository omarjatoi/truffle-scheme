package scheme.compiler;

import java.util.ArrayList;
import java.util.List;

import com.oracle.truffle.api.bytecode.BytecodeLocal;

import scheme.runtime.SchemeRootNodeGen;

/**
 * Emits every non-tail call site (AGENTS.md rule 2, design.md "Continuations"):
 *
 * <pre>
 * result = Call(procedure, arguments...)      ; calls, then runs the trampoline
 * IsCapture(result) ? Resumed(SuspendFrame(result))  ; a continuation is being captured: suspend this
 *                                                    ; frame; resumed, the value (or an error unwinding)
 *                   : result
 * </pre>
 *
 * The result is a value expression. Nothing else may emit {@code Call}.
 */
final class CallSiteEmitter {

    private CallSiteEmitter() {
    }

    /**
     * The tasks, in order, that emit a non-tail call. {@code procedure} and {@code arguments} emit
     * one value each.
     */
    static List<Runnable> nonTailCall(SchemeRootNodeGen.Builder b, Runnable procedure, List<Runnable> arguments) {
        BytecodeLocal[] result = new BytecodeLocal[1];
        List<Runnable> tasks = new ArrayList<>();
        tasks.add(() -> {
            b.beginBlock();
            result[0] = b.createLocal();
            b.beginStoreLocal(result[0]);
            b.beginCall();
        });
        tasks.add(procedure);
        tasks.addAll(arguments);
        tasks.add(() -> {
            b.endCall();
            b.endStoreLocal();
            b.beginConditional();
            b.beginIsCapture();
            b.emitLoadLocal(result[0]);
            b.endIsCapture();
            b.beginResumed();
            b.beginSuspendFrame();
            b.emitLoadLocal(result[0]);
            b.endSuspendFrame();
            b.endResumed();
            b.emitLoadLocal(result[0]);
            b.endConditional();
            b.endBlock();
        });
        return tasks;
    }
}
