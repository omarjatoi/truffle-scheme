package scheme.compiler;

import java.util.ArrayList;
import java.util.List;

import scheme.runtime.SchemeRootNodeGen;

/**
 * Emits every non-tail call site (AGENTS.md rule 2, design.md "Continuations"):
 *
 * <pre>
 * CallSite(                          ; short circuit: the first operand whose IsValue is true,
 *     Call(procedure, arguments...), ;   else the last: calls, then runs the trampoline
 *     Resumed(SuspendFrame()))       ; a capture: suspend this frame into it; resumed, the value
 *                                    ;   (or an error unwinding through the frame)
 * </pre>
 *
 * After the call that is {@code dup}, {@code IsValue}, the short circuit's branch, and on the
 * capture path the yield and {@code Resumed}: five instructions, no temporary (plan.md M7-02; it
 * was ten, with a local the result went through). The result is a value expression. Nothing else
 * may emit {@code Call}.
 */
final class CallSiteEmitter {

    private CallSiteEmitter() {
    }

    /**
     * The tasks, in order, that emit a non-tail call. {@code procedure} and {@code arguments} emit
     * one value each.
     */
    static List<Runnable> nonTailCall(SchemeRootNodeGen.Builder b, Runnable procedure, List<Runnable> arguments) {
        List<Runnable> tasks = new ArrayList<>();
        tasks.add(() -> {
            b.beginCallSite();
            b.beginCall();
        });
        tasks.add(procedure);
        tasks.addAll(arguments);
        tasks.add(() -> {
            b.endCall();
            b.beginResumed();
            b.emitSuspendFrame();
            b.endResumed();
            b.endCallSite();
        });
        return tasks;
    }
}
