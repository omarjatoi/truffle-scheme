package scheme.runtime;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * The root prompt around a program (design.md D6): runs the program and drives its continuation
 * ({@link PromptDriver}). Hand-written: it is a driver loop, not Scheme code.
 */
public final class PromptRootNode extends RootNode {

    private static final Object[] NO_ARGUMENTS = new Object[0];

    @Child private DirectCallNode body;
    @Child private PromptDriver driver;

    public PromptRootNode(SchemeLanguage language, CallTarget body) {
        super(language);
        this.body = DirectCallNode.create(body);
        this.driver = new PromptDriver();
    }

    @Override
    public Object execute(VirtualFrame frame) {
        SchemeContext context = SchemeContext.get(this);
        Object first;
        try {
            first = body.call(NO_ARGUMENTS);
        } catch (InvokeContinuation e) {
            context.setDynamicEnv(e.continuation.dynEnv);
            return driver.run(Capture.ROOT_TAG, e.value, e.continuation.segments, context);
        }
        return driver.run(Capture.ROOT_TAG, first, null, context);
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
