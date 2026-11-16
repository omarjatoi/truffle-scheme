package scheme.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

import scheme.runtime.value.SConstant;

/**
 * EXPLORATION (docs/interop-and-tools-exploration.md): a Scheme procedure called through interop,
 * by an embedder, another language, a host method we passed it to as a callback, or a tool. The
 * arguments are {@code [procedure, arg...]}, already Scheme values ({@link InteropValues#toScheme}).
 *
 * <p>The call runs under a root prompt of its own, as each REPL input does (design.md D6). The Java
 * (or other-language) frames between this prompt and any Scheme caller can't be captured (D5), so:
 *
 * <ul>
 * <li>{@code call/cc} inside the call captures up to this prompt, and its continuation can be
 * re-entered any number of times while the call is running;</li>
 * <li>the handlers start empty (the winders and the parameterization are the caller's), so an error
 * the call doesn't handle leaves it as a Truffle exception, crosses the foreign frames, and is raised
 * again at the Scheme call site on the other side ({@code (truffle interop)} does that): a
 * {@code guard} handler, which captures up to its prompt, could not reach across them;</li>
 * <li>a full continuation captured outside the call and invoked inside it escapes: it passes through
 * this prompt, unwinding the foreign frames, to the root prompt that captured it;</li>
 * <li>the caller's dynamic environment is restored on the way out, however the call ends.</li>
 * </ul>
 *
 * Hand-written, like {@link PromptRootNode}: it is a driver loop, not Scheme code.
 */
final class InteropCallRootNode extends RootNode {

    @Child private PromptDriver driver = new PromptDriver(true);

    InteropCallRootNode(SchemeLanguage language) {
        super(language);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] arguments = frame.getArguments();
        SchemeContext context = SchemeContext.get(this);
        DynEnv caller = context.getDynamicEnv();
        Object outerRun = context.rootRun;
        Object run = new Object();
        context.rootRun = run;
        context.setDynamicEnv(new DynEnv(caller.winders, caller.parameterization, SConstant.EMPTY_LIST));
        try {
            Object first;
            try {
                first = driver.callWithProcedure(arguments[0], arguments);
            } catch (InvokeContinuation e) {
                if (e.continuation.owner != run) {
                    throw e;
                }
                context.setDynamicEnv(e.continuation.dynEnv);
                return driver.run(Capture.ROOT_TAG, e.value, e.continuation.segments, context);
            }
            return driver.run(Capture.ROOT_TAG, first, null, context);
        } finally {
            context.rootRun = outerRun;
            context.setDynamicEnv(caller);
        }
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
