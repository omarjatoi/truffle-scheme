package scheme.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * {@code (%call-with-prompt tag thunk)} (design.md D4): calls thunk inside a prompt with tag. A
 * runtime procedure, not a builtin: a prompt is where a Java frame sits between Scheme frames by
 * design, and crossing it with a capture turns it into a flat marker ({@link PromptDriver}).
 */
public final class PromptProcedureRootNode extends RootNode {

    @Child private PromptDriver driver;

    PromptProcedureRootNode(SchemeLanguage language) {
        super(language);
        this.driver = new PromptDriver();
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] arguments = frame.getArguments();
        if (arguments.length != 3) {
            throw SchemeRootNode.arityError(2, 2, arguments.length - 1);
        }
        SchemeContext context = SchemeContext.get(this);
        Object first = driver.callThunk(arguments[2]);
        return driver.run(arguments[1], first, null, context);
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
