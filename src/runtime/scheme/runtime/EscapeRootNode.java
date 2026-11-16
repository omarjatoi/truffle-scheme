package scheme.runtime;

import java.util.Arrays;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
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
 * <li>Invoking k throws an {@link EscapeException} to that prompt, which returns k's arguments. When
 * k is tail-called back to the prompt's own driver loop, the driver returns them without a
 * throw.</li>
 * <li>k is invoked in call/cc's dynamic environment, as the full continuation would be: if the
 * current one differs, k first tail-calls the prelude's {@code %escape}, which rewinds (running
 * after-thunks, design.md "Dynamic environment") and invokes k again.</li>
 * <li>A capture that crosses the prompt (call/cc, guard, a spill, torture) flattens it into a
 * prompt marker, like any prompt ({@link PromptDriver}). Invoking k from a resumed segment then
 * throws to the driver that holds the marker, which drops the pending segments up to it. So k
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
        super(language);
        this.driver = new PromptDriver();
        this.procedure = new Procedure(language).getCallTarget();
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] arguments = frame.getArguments();
        if (arguments.length != 2) {
            throw SchemeRootNode.arityError(1, 1, arguments.length - 1);
        }
        SchemeContext context = SchemeContext.get(this);
        Target target = new Target();
        // This prompt's Java frames count as a level of nesting for the stack-depth spill.
        context.callDepth++;
        try {
            Object first;
            try {
                first = driver.callWithProcedure(arguments[1], new Object[]{null, continuation(target, context)});
            } catch (EscapeException e) {
                if (e.target != target) {
                    throw e;
                }
                return e.value;
            }
            return driver.run(target, first, null, context);
        } finally {
            context.callDepth--;
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
            if (self.captured[1] == context.getDynamicEnv()) {
                throw new EscapeException((Target) self.captured[0], values(arguments));
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
