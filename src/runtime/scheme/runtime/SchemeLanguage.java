package scheme.runtime;

import java.util.ServiceLoader;

import org.graalvm.options.OptionCategory;
import org.graalvm.options.OptionDescriptors;
import org.graalvm.options.OptionKey;
import org.graalvm.options.OptionStability;
import org.graalvm.options.OptionType;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.Option;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.debug.DebuggerTags;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.instrumentation.AllocationReporter;
import com.oracle.truffle.api.instrumentation.ProvidedTags;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.nodes.ExecutableNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.Source;

import scheme.runtime.value.RecordShapes;
import scheme.runtime.value.SClosure;

// DebuggerTags.AlwaysHalt: every debugger session (DAP, the Chrome inspector) installs an AlwaysHalt
// breakpoint, and the Bytecode DSL rejects a tag the language doesn't provide. No node carries it.
@ProvidedTags({DebuggerTags.AlwaysHalt.class, StandardTags.RootTag.class, StandardTags.RootBodyTag.class, StandardTags.StatementTag.class, StandardTags.CallTag.class,
                StandardTags.ExpressionTag.class})
@TruffleLanguage.Registration(id = SchemeLanguage.ID, name = "R7RS Scheme", defaultMimeType = SchemeLanguage.MIME_TYPE, characterMimeTypes = SchemeLanguage.MIME_TYPE, contextPolicy = TruffleLanguage.ContextPolicy.EXCLUSIVE)
public final class SchemeLanguage extends TruffleLanguage<SchemeContext> {

    public static final String ID = "scheme";
    public static final String MIME_TYPE = "application/x-scheme";

    private static final Object[] NO_CAPTURES = new Object[0];
    private static final LanguageReference<SchemeLanguage> REFERENCE = LanguageReference.create(SchemeLanguage.class);

    /** Where user libraries are found (design.md D7): directories, separated by the path separator. */
    @Option(name = "LibraryPath", help = "Directories to search for libraries, separated by the path separator (-I).", category = OptionCategory.USER, stability = OptionStability.STABLE) //
    public static final OptionKey<String> LIBRARY_PATH = new OptionKey<>("");

    /**
     * Whether a context may restore the standard libraries from the prelude image (design.md D13):
     * false expands them, and the context takes no part in building a per-JVM image.
     */
    @Option(name = "PreludeImage", help = "Restore the standard libraries from the prelude image instead of expanding them.", category = OptionCategory.INTERNAL, stability = OptionStability.STABLE) //
    public static final OptionKey<Boolean> PRELUDE_IMAGE = new OptionKey<>(true);

    /**
     * Spill past this many nested non-tail calls, however much Java stack there is (0, the default:
     * from the stack the thread has, StackBudget). For tests that need frames on the heap.
     */
    @Option(name = "SpillDepth", help = "Spill deep recursion to the heap past this many nested calls (0: from the Java stack available).", category = OptionCategory.INTERNAL, stability = OptionStability.EXPERIMENTAL) //
    public static final OptionKey<Integer> SPILL_DEPTH = new OptionKey<>(0);

    /**
     * The Java stack size of the thread the context is created on, when the embedder knows it (it
     * started that thread with that size): on that thread, the spill limit comes from it instead of
     * a measurement of the stack (StackBudget). 0, the default: measured. The {@code scheme}
     * launcher passes its evaluation thread's (design.md D17).
     */
    @Option(name = "StackSize", help = "The Java stack size of the thread the context is created on, such as 256m (0: unknown, measured when needed).", category = OptionCategory.EXPERT, stability = OptionStability.STABLE, usageSyntax = "<size>") //
    public static final OptionKey<Long> STACK_SIZE = new OptionKey<>(0L, new OptionType<>("size", StackBudget::parseStackSize));

    private SchemeCompiler compiler;
    /** What runs a procedure called through interop (design.md D10): see {@link #interopCallTarget}. */
    @CompilationFinal private RootCallTarget interopCall;
    /** The Static Object Model shapes of records (design.md D1). */
    private final RecordShapes recordShapes = new RecordShapes(this);
    /**
     * Valid until an allocation listener ({@code --memtracer}) is active in a context of this
     * language: until then nothing reports allocations, at no cost ({@link Allocations}).
     */
    final Assumption noAllocationListeners = Assumption.create("no allocation listeners");

    @Override
    protected SchemeContext createContext(Env env) {
        SchemeContext context = new SchemeContext(env);
        listenForAllocationListeners(context);
        return context;
    }

    /**
     * Adds the context's listener to its allocation reporter, which is the engine's and shared by
     * its contexts: {@link #disposeContext} removes it again (plan.md M8-02).
     */
    private void listenForAllocationListeners(SchemeContext context) {
        Assumption noListeners = noAllocationListeners;
        context.allocationListener = active -> {
            if (active) {
                noListeners.invalidate();
            }
        };
        context.allocationReporter.addActiveListener(context.allocationListener);
        if (context.allocationReporter.isActive()) {
            noAllocationListeners.invalidate();
        }
    }

    /**
     * A context pre-initialized in a native image (design.md D17, plan.md M8-07) is taken over by
     * the run's first context: its standard libraries, restored from the prelude image at image
     * build time, stay, and everything that came from the build's Env is rebound to the run's
     * ({@link SchemeContext#patch}, and the compiler's library path). A run that doesn't restore
     * from the prelude image ({@link #PRELUDE_IMAGE} false) gets a new context instead.
     */
    @Override
    protected boolean patchContext(SchemeContext context, Env newEnv) {
        if (!newEnv.getOptions().get(PRELUDE_IMAGE)) {
            return false;
        }
        AllocationReporter before = context.allocationReporter;
        context.patch(newEnv);
        if (context.allocationReporter != before) {
            before.removeActiveListener(context.allocationListener);
            listenForAllocationListeners(context);
        }
        compiler().patchContext(context);
        return true;
    }

    /**
     * The allocation reporter is the engine's, shared by its contexts: a closed context's listener
     * is removed from it, or an embedder's shared engine would keep every closed context's language
     * instance, and with it the instance's code (plan.md M8-02).
     */
    @Override
    protected void disposeContext(SchemeContext context) {
        context.allocationReporter.removeActiveListener(context.allocationListener);
    }

    @Override
    protected void initializeContext(SchemeContext context) {
        context.globals.cell(context.symbols.intern("%call-with-prompt")).define(new SClosure(new PromptProcedureRootNode(this).getCallTarget(), NO_CAPTURES));
        context.globals.cell(context.symbols.intern("%call/ec")).define(new SClosure(new EscapeRootNode(this).getCallTarget(), NO_CAPTURES));
        for (BuiltinLibrary library : ServiceLoader.load(BuiltinLibrary.class, SchemeLanguage.class.getClassLoader())) {
            for (PrimitiveSpec spec : library.primitives()) {
                SClosure procedure = new SClosure(new PrimitiveRootNode(this, spec).getCallTarget(), NO_CAPTURES);
                GlobalCell cell = context.globals.cell(context.symbols.intern(spec.name()));
                if (cell.valueOrNull() != null) {
                    // Two fan-out areas (AGENTS.md "Areas") registered the same name.
                    throw new IllegalStateException("primitive " + spec.name() + " is registered twice");
                }
                cell.define(procedure);
                cell.fix();
            }
        }
        compiler().initializeContext(this, context);
        if (interopCall == null) {
            // Here, never from compiled code (nodes can't be created there).
            interopCall = new InteropCallRootNode(this).getCallTarget();
        }
    }

    public static SchemeLanguage get(Node node) {
        return REFERENCE.get(node);
    }

    /** The shapes of this language's records (design.md D1, plan.md M7-04). */
    public RecordShapes recordShapes() {
        return recordShapes;
    }

    /** The front end, for {@code eval} and {@code environment} (design.md D6). */
    public SchemeCompiler compiler() {
        if (compiler == null) {
            compiler = ServiceLoader.load(SchemeCompiler.class, SchemeLanguage.class.getClassLoader()).findFirst().orElseThrow(
                            () -> new IllegalStateException("no SchemeCompiler service: is the scheme.compiler project in the distribution?"));
        }
        return compiler;
    }

    /** Single-threaded contexts (design.md D10). */
    @Override
    protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) {
        return singleThreaded;
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        return program(SchemeContext.get(null), request.getSource());
    }

    /**
     * Code evaluated in a suspended frame of a Scheme procedure (the debugger's
     * {@code DebugStackFrame.eval}, plan.md M6-05): the forms run as a body whose variables are the
     * frame's ({@link FrameVariables}: what its scope shows) inside the frame's top level: an
     * isolated program's own (plan.md M7-07), else the interaction environment (design.md D15).
     * Elsewhere, or without a frame, Truffle evaluates it as a program.
     */
    @Override
    protected ExecutableNode parse(InlineParsingRequest request) {
        if (request.getFrame() == null) {
            return null;
        }
        FrameVariables variables = FrameVariables.at(request.getLocation(), request.getFrame(), true);
        if (variables == null) {
            return null;
        }
        MaterializedFrame own = FrameVariables.resolve(variables.bytecode, request.getFrame());
        boolean[] boxed = new boolean[variables.names.length];
        for (int k = 0; k < boxed.length; k++) {
            boxed[k] = variables.raw(own, k) instanceof Box;
        }
        Object topLevel = ((SchemeRootNode) variables.bytecode.getBytecodeRootNode()).getTopLevel();
        SClosure procedure = compiler().compileInFrame(this, SchemeContext.get(null), request.getSource(), variables.names, boxed, topLevel);
        return new FrameEvalNode(this, variables, procedure);
    }

    /**
     * A program with its prompt around it (design.md D6). An interactive source (REPL input, design.md
     * D17) also writes the values of each of its top-level forms, as each completes (plan.md M7-07):
     * the compiler wraps each in a call to {@code %repl-write}.
     */
    private CallTarget program(SchemeContext context, Source source) {
        return withPrompt(compiler().compileProgram(this, context, source));
    }

    /**
     * The language writes the values of interactive sources itself ({@link #program}), so Truffle
     * never prints them again.
     */
    @Override
    protected boolean isVisible(SchemeContext context, Object value) {
        return false;
    }

    /** A program or library body with its prompt around it (design.md D6). */
    public CallTarget withPrompt(CallTarget body) {
        return new PromptRootNode(this, body).getCallTarget();
    }

    /**
     * What runs a Scheme procedure that an embedder or another language calls through interop
     * ({@code InteropLibrary.execute}): the procedure under a root prompt of its own (design.md D6,
     * D10), {@link InteropCallRootNode}. Arguments: {@code [procedure, arg...]}.
     */
    public RootCallTarget interopCallTarget() {
        return interopCall;
    }

    /**
     * The top scope ({@code context.getBindings("scheme")}, and the debugger's global scope): the
     * interaction environment's variables (design.md D10, D15).
     */
    @Override
    protected Object getScope(SchemeContext context) {
        return new TopScope(context, compiler());
    }

    /** Scheme's view of a primitive or foreign value: shown as Scheme writes it (design.md D10). */
    @Override
    protected Object getLanguageView(SchemeContext context, Object value) {
        return new LanguageView(value);
    }

    @Override
    protected OptionDescriptors getOptionDescriptors() {
        return new SchemeLanguageOptionDescriptors();
    }

}
