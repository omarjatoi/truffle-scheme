package scheme.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExecutableNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;

import scheme.runtime.value.SClosure;
import scheme.runtime.value.SConstant;

/**
 * Code evaluated in a suspended frame ({@link SchemeLanguage#parse(com.oracle.truffle.api.TruffleLanguage.InlineParsingRequest)}, the
 * debugger's {@code DebugStackFrame.eval}, plan.md M6-05): a procedure whose parameters are the
 * frame's {@link FrameVariables}, called with their values, under a root prompt of its own as an
 * interop call is (design.md D6, D10). A variable the program assigns is passed as its Box, which
 * the procedure uses as its own, so {@code set!} in the evaluated code writes it; the compiler
 * rejects {@code set!} of any other variable, as a debugger's write of it would be (FrameScope).
 * Hand-written: it is a debugger's entry point, not Scheme code.
 */
final class FrameEvalNode extends ExecutableNode {

    private final FrameVariables variables;
    private final SClosure procedure;
    @Child private IndirectCallNode call = IndirectCallNode.create();

    FrameEvalNode(SchemeLanguage language, FrameVariables variables, SClosure procedure) {
        super(language);
        this.variables = variables;
        this.procedure = procedure;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return call.call(SchemeLanguage.get(this).interopCallTarget(), arguments(frame.materialize()));
    }

    @TruffleBoundary
    private Object[] arguments(MaterializedFrame frame) {
        MaterializedFrame own = FrameVariables.resolve(variables.bytecode, frame);
        Object[] arguments = new Object[variables.names.length + 1];
        arguments[0] = procedure;
        for (int k = 0; k < variables.names.length; k++) {
            Object raw = variables.raw(own, k);
            arguments[k + 1] = raw == null ? SConstant.UNSPECIFIED : raw;
        }
        return arguments;
    }
}
