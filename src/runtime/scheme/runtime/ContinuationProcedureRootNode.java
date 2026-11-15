package scheme.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

import scheme.runtime.value.MultipleValues;
import scheme.runtime.value.SClosure;

/**
 * The procedure a continuation is passed to Scheme as: an {@link SClosure} over this root with the
 * {@link Continuation} as its one captured value. Calling it throws {@link InvokeContinuation}.
 * Hand-written: it only packages its arguments.
 */
public final class ContinuationProcedureRootNode extends RootNode {

    ContinuationProcedureRootNode(SchemeLanguage language) {
        super(language);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] arguments = frame.getArguments();
        Continuation k = (Continuation) ((SClosure) arguments[0]).captured[0];
        Object value;
        if (arguments.length == 2) {
            value = arguments[1];
        } else {
            Object[] values = new Object[arguments.length - 1];
            System.arraycopy(arguments, 1, values, 0, values.length);
            value = new MultipleValues(values);
        }
        if (k.composable) {
            // Pushed onto the current continuation: travels up to the nearest prompt (design.md D4).
            return Capture.reinstate(k, value, SchemeContext.get(this).getDynamicEnv());
        }
        throw new InvokeContinuation(k, value);
    }

    @Override
    public String getName() {
        return "continuation";
    }

    @Override
    public String toString() {
        return "continuation";
    }
}
