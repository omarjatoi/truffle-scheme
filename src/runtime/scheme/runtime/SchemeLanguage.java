package scheme.runtime;

import java.util.ServiceLoader;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;

import scheme.runtime.value.SClosure;

@TruffleLanguage.Registration(id = SchemeLanguage.ID, name = "R7RS Scheme", defaultMimeType = SchemeLanguage.MIME_TYPE, characterMimeTypes = SchemeLanguage.MIME_TYPE, contextPolicy = TruffleLanguage.ContextPolicy.EXCLUSIVE)
public final class SchemeLanguage extends TruffleLanguage<SchemeContext> {

    public static final String ID = "scheme";
    public static final String MIME_TYPE = "application/x-scheme";

    private static final Object[] NO_CAPTURES = new Object[0];

    @Override
    protected SchemeContext createContext(Env env) {
        return new SchemeContext(env);
    }

    @Override
    protected void initializeContext(SchemeContext context) {
        for (BuiltinLibrary library : ServiceLoader.load(BuiltinLibrary.class, SchemeLanguage.class.getClassLoader())) {
            for (PrimitiveSpec spec : library.primitives()) {
                SClosure procedure = new SClosure(new PrimitiveRootNode(this, spec).getCallTarget(), NO_CAPTURES);
                context.globals.cell(context.symbols.intern(spec.name())).define(procedure);
            }
        }
    }

    /** Single-threaded contexts (design.md D10). */
    @Override
    protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) {
        return singleThreaded;
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        SchemeCompiler compiler = ServiceLoader.load(SchemeCompiler.class, SchemeLanguage.class.getClassLoader()).findFirst().orElseThrow(
                        () -> new IllegalStateException("no SchemeCompiler service: is the scheme.compiler project in the distribution?"));
        return compiler.compileProgram(this, SchemeContext.get(null), request.getSource());
    }
}
