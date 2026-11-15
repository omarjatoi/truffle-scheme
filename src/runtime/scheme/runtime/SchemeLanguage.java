package scheme.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ServiceLoader;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.source.Source;

import scheme.runtime.value.SClosure;

@TruffleLanguage.Registration(id = SchemeLanguage.ID, name = "R7RS Scheme", defaultMimeType = SchemeLanguage.MIME_TYPE, characterMimeTypes = SchemeLanguage.MIME_TYPE, contextPolicy = TruffleLanguage.ContextPolicy.EXCLUSIVE)
public final class SchemeLanguage extends TruffleLanguage<SchemeContext> {

    public static final String ID = "scheme";
    public static final String MIME_TYPE = "application/x-scheme";

    private static final Object[] NO_CAPTURES = new Object[0];
    /** The prelude's Scheme sources: resources of the scheme.prelude project. */
    private static final String PRELUDE = "/scheme/prelude/";

    private SchemeCompiler compiler;

    @Override
    protected SchemeContext createContext(Env env) {
        return new SchemeContext(env);
    }

    @Override
    protected void initializeContext(SchemeContext context) {
        context.globals.cell(context.symbols.intern("%call-with-prompt")).define(new SClosure(new PromptProcedureRootNode(this).getCallTarget(), NO_CAPTURES));
        for (BuiltinLibrary library : ServiceLoader.load(BuiltinLibrary.class, SchemeLanguage.class.getClassLoader())) {
            for (PrimitiveSpec spec : library.primitives()) {
                SClosure procedure = new SClosure(new PrimitiveRootNode(this, spec).getCallTarget(), NO_CAPTURES);
                context.globals.cell(context.symbols.intern(spec.name())).define(procedure);
            }
        }
        loadPrelude(context);
    }

    /**
     * Runs the prelude's files, in the order {@code load-order} lists them, as programs in the new
     * context (M2-01). Core forms only until the expander and libraries (M3). These are the
     * implementation's own bundled sources, not user I/O, so they are read as class resources.
     */
    private void loadPrelude(SchemeContext context) {
        for (String file : readPrelude("load-order").lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList()) {
            Source source = Source.newBuilder(ID, readPrelude(file), "prelude/" + file).internal(true).build();
            program(context, source).call();
        }
    }

    private static String readPrelude(String name) {
        try (InputStream in = SchemeLanguage.class.getResourceAsStream(PRELUDE + name)) {
            if (in == null) {
                throw new IllegalStateException("missing prelude resource " + PRELUDE + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read prelude resource " + PRELUDE + name, e);
        }
    }

    private SchemeCompiler compiler() {
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
        return new PromptRootNode(this, compiler().compileProgram(this, context, source)).getCallTarget();
    }

}
