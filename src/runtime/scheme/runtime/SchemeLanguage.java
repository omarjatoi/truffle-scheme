package scheme.runtime;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;

/**
 * M0-01 stub: evaluates a single integer literal. The real front end (reader, expander, Bytecode DSL
 * compiler) replaces {@link #parse} in M1.
 */
@TruffleLanguage.Registration(id = SchemeLanguage.ID, name = "R7RS Scheme", defaultMimeType = SchemeLanguage.MIME_TYPE, characterMimeTypes = SchemeLanguage.MIME_TYPE, contextPolicy = TruffleLanguage.ContextPolicy.SHARED)
public final class SchemeLanguage extends TruffleLanguage<SchemeContext> {

    public static final String ID = "scheme";
    public static final String MIME_TYPE = "application/x-scheme";

    @Override
    protected SchemeContext createContext(Env env) {
        return new SchemeContext(env);
    }

    /** Single-threaded contexts (design.md D10). */
    @Override
    protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) {
        return singleThreaded;
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        String text = request.getSource().getCharacters().toString().strip();
        long value;
        try {
            value = Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new SchemeError("the M0 stub only evaluates an integer literal, got: " + text);
        }
        return new LiteralRootNode(this, value).getCallTarget();
    }
}
