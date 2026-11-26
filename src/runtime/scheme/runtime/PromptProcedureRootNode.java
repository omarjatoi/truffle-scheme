package scheme.runtime;

import java.util.List;

import com.oracle.truffle.api.TruffleStackTraceElement;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * {@code (%call-with-prompt tag thunk)} (design.md D4): calls thunk inside a prompt with tag. A
 * runtime procedure, not a builtin: a prompt is where a Java frame sits between Scheme frames by
 * design, and crossing it with a capture turns it into a flat marker ({@link PromptDriver}).
 */
public final class PromptProcedureRootNode extends RootNode {

    @Child private PromptDriver driver;

    PromptProcedureRootNode(SchemeLanguage language) {
        super(language, PromptDriver.frameDescriptor());
        this.driver = new PromptDriver();
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] arguments = frame.getArguments();
        if (arguments.length != 3) {
            throw SchemeRootNode.arityError(this, 2, 2, arguments.length - 1);
        }
        SchemeContext context = SchemeContext.get(this);
        // This prompt's Java frames, and its call of thunk, count toward the stack use (StackBudget).
        int use = context.stackUse;
        context.stackUse = use + StackBudget.promptCost(context);
        try {
            Object first = driver.callThunk(arguments[2]);
            return driver.run(frame, arguments[1], first, null, context);
        } finally {
            context.stackUse = use;
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

    /**
     * The standard libraries' code, as the prelude's frames are (design.md D8): left out of stack
     * traces and skipped by an error's location. A root with no source section isn't internal by
     * default, so the trace showed it, as {@code %call-with-prompt ?}.
     */
    @Override
    public boolean isInternal() {
        return true;
    }

    @Override
    public String getName() {
        return "%call-with-prompt";
    }

    @Override
    public String toString() {
        return "%call-with-prompt";
    }
}
