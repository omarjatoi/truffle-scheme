package scheme.runtime;

import java.util.Arrays;
import java.util.List;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleStackTraceElement;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;

import scheme.runtime.value.MultipleValues;
import scheme.runtime.value.SClosure;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SPair;

/**
 * {@code (%call/ec receiver)}: {@code call/cc} for a receiver whose continuation the compiler
 * proved escape-only (M5-04, design.md "Continuations"; the proof is the compiler's
 * EscapeAnalysis). Instead of capturing, it calls the receiver, with an escape procedure k, inside a
 * prompt of its own whose tag is a fresh {@link Target}:
 *
 * <ul>
 * <li>Invoking k returns an ESCAPE {@link Capture} for that prompt, which travels up the stack like
 * every other capture (each frame it passes is abandoned) and makes the prompt return k's
 * arguments. When k is tail-called back to the prompt's own driver loop, the driver returns them
 * without a capture. It isn't a Java exception: one thrown through the host-compiled interpreter
 * made the host compiler deoptimize it again and again (M9-04, design.md "Continuations").</li>
 * <li>k is invoked in call/cc's dynamic environment, as the full continuation would be: if the
 * current one differs, k first tail-calls the prelude's {@code %escape}, which rewinds (running
 * after-thunks, design.md "Dynamic environment") and invokes k again.</li>
 * <li>A capture that crosses the prompt (call/cc, guard, a spill, torture) flattens it into a
 * prompt marker, like any prompt ({@link PromptDriver}). Invoking k from a resumed segment then
 * returns to the driver that holds the marker, which drops the pending segments up to it. So k
 * works from every copy of a continuation captured inside the receiver's extent.</li>
 * <li>A {@code %call/ec} in tail position of the receiver has this prompt's continuation, so the
 * driver runs it in this prompt (R7RS §3.5: call/cc calls its receiver in tail position).</li>
 * </ul>
 *
 * Hand-written, like {@code %call-with-prompt}: a prompt is where a Java frame sits between Scheme
 * frames by design (D5).
 */
public final class EscapeRootNode extends RootNode {

    /** The tag of one {@code %call/ec} prompt: what its escape procedures throw to. */
    public static final class Target {
        @Override
        public String toString() {
            return "call/ec";
        }
    }

    @Child private PromptDriver driver;
    /** The code of every escape procedure (k): {@link Procedure}. */
    private final RootCallTarget procedure;

    EscapeRootNode(SchemeLanguage language) {
        super(language, PromptDriver.frameDescriptor());
        this.driver = new PromptDriver();
        this.procedure = new Procedure(language).getCallTarget();
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] arguments = frame.getArguments();
        if (arguments.length != 2) {
            throw SchemeRootNode.arityError(this, 1, 1, arguments.length - 1);
        }
        SchemeContext context = SchemeContext.get(this);
        Target target = new Target();
        // This prompt's Java frames count toward the stack use (StackBudget).
        int use = context.stackUse;
        context.stackUse = use + StackBudget.promptCost(context);
        try {
            Object first = driver.callWithProcedure(arguments[1], new Object[]{null, continuation(target, context)});
            return driver.run(frame, target, first, null, context);
        } finally {
            context.stackUse = use;
        }
    }

    /** A new escape procedure for this prompt: k, in the current dynamic environment. */
    SClosure continuation(Target target, SchemeContext context) {
        return new SClosure(procedure, new Object[]{target, context.getDynamicEnv()});
    }

    /** Whether calling {@code procedure} would escape to {@code target} at once, without rewinding first. */
    static boolean escapesTo(Object procedure, Target target, SchemeContext context) {
        return procedure instanceof SClosure k && k.callTarget.getRootNode() instanceof Procedure && k.captured[0] == target && k.captured[1] == context.getDynamicEnv();
    }

    /** The values an escape procedure called with {@code arguments} (procedure first) passes on. */
    static Object values(Object[] arguments) {
        return arguments.length == 2 ? arguments[1] : new MultipleValues(Arrays.copyOfRange(arguments, 1, arguments.length));
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
     * default, so the trace showed it, as {@code call/cc ?}.
     */
    @Override
    public boolean isInternal() {
        return true;
    }

    @Override
    public String getName() {
        return "call/cc";
    }

    @Override
    public String toString() {
        return "call/cc";
    }

    /** The code of an escape procedure; its closure holds its {@link Target} and dynamic environment. */
    static final class Procedure extends RootNode {

        Procedure(SchemeLanguage language) {
            super(language);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object[] arguments = frame.getArguments();
            SClosure self = (SClosure) arguments[0];
            SchemeContext context = SchemeContext.get(this);
            DynEnv env = context.getDynamicEnv();
            if (self.captured[1] == env) {
                return Capture.escape((Target) self.captured[0], values(arguments), env);
            }
            // Leaving dynamic-wind extents, or other bindings: rewind first, in Scheme (D5).
            return rewindFirst(context.escape, self, arguments);
        }

        @TruffleBoundary
        private static TailCall rewindFirst(Object escape, SClosure self, Object[] arguments) {
            if (escape == null) {
                throw new IllegalStateException("the prelude has not installed %escape");
            }
            Object values = SConstant.EMPTY_LIST;
            for (int i = arguments.length - 1; i >= 1; i--) {
                values = new SPair(arguments[i], values);
            }
            return new TailCall(escape, new Object[]{escape, self, self.captured[1], values});
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
}
