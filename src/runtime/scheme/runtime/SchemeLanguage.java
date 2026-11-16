package scheme.runtime;

import java.util.ServiceLoader;

import org.graalvm.options.OptionCategory;
import org.graalvm.options.OptionDescriptors;
import org.graalvm.options.OptionKey;
import org.graalvm.options.OptionStability;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Option;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.debug.DebuggerTags;
import com.oracle.truffle.api.instrumentation.ProvidedTags;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.Source;

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

    private SchemeCompiler compiler;

    @Override
    protected SchemeContext createContext(Env env) {
        return new SchemeContext(env);
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
            }
        }
        compiler().initializeContext(this, context);
    }

    public static SchemeLanguage get(Node node) {
        return REFERENCE.get(node);
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

    /** A program with its prompt around it (design.md D6). */
    private CallTarget program(SchemeContext context, Source source) {
        return withPrompt(compiler().compileProgram(this, context, source));
    }

    /** A program or library body with its prompt around it (design.md D6). */
    public CallTarget withPrompt(CallTarget body) {
        return new PromptRootNode(this, body).getCallTarget();
    }

    @Override
    protected OptionDescriptors getOptionDescriptors() {
        return new SchemeLanguageOptionDescriptors();
    }

}
