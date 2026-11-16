package scheme.runtime;

import java.util.List;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleStackTraceElement;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;

/**
 * The root prompt around a program (design.md D6): runs the program and drives its continuation
 * ({@link PromptDriver}). Hand-written: it is a driver loop, not Scheme code.
 */
public final class PromptRootNode extends RootNode {

    private static final Object[] NO_ARGUMENTS = new Object[0];

    @Child private DirectCallNode body;
    @Child private PromptDriver driver;

    public PromptRootNode(SchemeLanguage language, CallTarget body) {
        super(language, PromptDriver.frameDescriptor());
        this.body = DirectCallNode.create(body);
        this.driver = new PromptDriver();
    }

    /** The program's own source section (design.md D13), so the prompt's frame has a location too. */
    @Override
    public SourceSection getSourceSection() {
        return ((com.oracle.truffle.api.RootCallTarget) body.getCallTarget()).getRootNode().getSourceSection();
    }

    @Override
    public Object execute(VirtualFrame frame) {
        SchemeContext context = SchemeContext.get(this);
        // An error that leaves the program runs the after-thunks it skips (design.md D8), and
        // leaves the dynamic environment as it found it, so the next program or interop call
        // (D10) doesn't run with its parameters or handlers.
        DynEnv outer = context.getDynamicEnv();
        StackBudget.enterRoot(context);
        try {
            Object first;
            try {
                first = body.call(NO_ARGUMENTS);
            } catch (InvokeContinuation e) {
                context.setDynamicEnv(e.continuation.dynEnv);
                return driver.run(frame, Capture.ROOT_TAG, e.value, e.continuation.segments, context);
            }
            return driver.run(frame, Capture.ROOT_TAG, first, null, context);
        } catch (SchemeError e) {
            return driver.leave(frame, e, outer, context);
        } finally {
            context.setDynamicEnv(outer);
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
        return "<prompt>";
    }

    @Override
    public String toString() {
        return "<prompt>";
    }
}
