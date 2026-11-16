package scheme.runtime;

import java.util.List;

import com.oracle.truffle.api.TruffleStackTraceElement;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * A Scheme procedure called through interop ({@code InteropLibrary.execute}: an embedder, another
 * language, a tool), design.md D10. The arguments are {@code [procedure, arg...]}, already Scheme
 * values ({@link InteropValues#toScheme}).
 *
 * <p>The call runs under a root prompt of its own, as a program or a REPL input does (D6), so it
 * never hands a {@code TailCall} or a {@code Capture} to its caller, and:
 *
 * <ul>
 * <li>{@code call/cc} inside the call captures up to this prompt, and its continuation can be
 * re-entered any number of times, during the call or in a later one: invoked later (from Java or
 * from Scheme), it runs the rest of the call that captured it, and the later call returns what that
 * rest returns, as a continuation captured in one REPL input does in another;</li>
 * <li>an error the call doesn't handle leaves it as a {@link SchemeError} (a guest exception to the
 * embedder), and {@code exit} as an exit (D13);</li>
 * <li>however the call ends, the dynamic environment is the caller's again afterwards.</li>
 * </ul>
 *
 * Hand-written, like {@link PromptRootNode}: it is a driver loop, not Scheme code. It is not a
 * builtin (rule 1): no Scheme frame sits below it, since Scheme code can't call foreign code (D10).
 */
final class InteropCallRootNode extends RootNode {

    @Child private PromptDriver driver = new PromptDriver();

    InteropCallRootNode(SchemeLanguage language) {
        super(language, PromptDriver.frameDescriptor());
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] arguments = frame.getArguments();
        SchemeContext context = SchemeContext.get(this);
        DynEnv caller = context.getDynamicEnv();
        try {
            Object first;
            try {
                first = driver.callWithProcedure(arguments[0], arguments);
            } catch (InvokeContinuation e) {
                // A continuation the procedure invoked directly (it may be one).
                context.setDynamicEnv(e.continuation.dynEnv);
                return driver.run(frame, Capture.ROOT_TAG, e.value, e.continuation.segments, context);
            }
            return driver.run(frame, Capture.ROOT_TAG, first, null, context);
        } finally {
            context.setDynamicEnv(caller);
        }
    }

    /** The frames pending in this prompt, on the heap (plan.md M6-04): tools show them after it. */
    @Override
    protected List<TruffleStackTraceElement> findAsynchronousFrames(Frame frame) {
        return PromptDriver.pendingFrames(frame);
    }

    /**
     * No instrumentable node: so a debugger lists this frame (it skips a frame whose call node isn't
     * in instrumented code), and finds the frames pending in it (plan.md M6-04).
     */
    @Override
    protected Node findInstrumentableCallNode(Node callNode, Frame frame, int bytecodeIndex) {
        return null;
    }

    @Override
    public String getName() {
        return "<interop-call>";
    }

    @Override
    public String toString() {
        return "<interop-call>";
    }
}
