package scheme.runtime;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
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
        super(language);
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
        Object outerRun = context.rootRun;
        context.rootRun = new Object();
        try {
            Object first;
            try {
                first = body.call(NO_ARGUMENTS);
            } catch (InvokeContinuation e) {
                context.setDynamicEnv(e.continuation.dynEnv);
                return driver.run(Capture.ROOT_TAG, e.value, e.continuation.segments, context);
            }
            return driver.run(Capture.ROOT_TAG, first, null, context);
        } finally {
            context.rootRun = outerRun;
        }
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
