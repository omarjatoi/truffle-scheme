package scheme.test.upstream;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.debug.DebuggerTags;
import com.oracle.truffle.api.instrumentation.ProvidedTags;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.source.Source;

/**
 * A language that has nothing to do with Scheme, for reproducing Truffle bugs (docs/upstream.md):
 * whatever its source text, a program is one statement that returns 42. Each {@code (} it starts
 * with nests the 42 one expression deeper: in {@code ((x))} it is in an expression in an expression
 * (UpstreamAstDepthTest). Its roots are {@link UpstreamRoot}s. Its context holds only its
 * {@code Env} (UpstreamExitStackTraceTest exits through it).
 */
// The debugger asks every language for AlwaysHalt, and a Bytecode DSL root fails for a tag its
// language doesn't provide (UpstreamUnprovidedTagTest).
@ProvidedTags({DebuggerTags.AlwaysHalt.class, StandardTags.RootTag.class, StandardTags.RootBodyTag.class, StandardTags.StatementTag.class,
                StandardTags.ExpressionTag.class})
@TruffleLanguage.Registration(id = UpstreamLanguage.ID, name = "Upstream reproducers")
public final class UpstreamLanguage extends TruffleLanguage<UpstreamLanguage.UpstreamContext> {

    public static final String ID = "upstream-repro";

    /** The language's context: its {@code Env}. */
    public static final class UpstreamContext {
        Env env;

        UpstreamContext(Env env) {
            this.env = env;
        }

        public Env env() {
            return env;
        }
    }

    @Override
    protected UpstreamContext createContext(Env env) {
        return new UpstreamContext(env);
    }

    /**
     * Accepts any pre-initialized context (UpstreamPreinitializationDeadlockTest's control case):
     * only its {@code Env} to rebind.
     */
    @Override
    protected boolean patchContext(UpstreamContext context, Env newEnv) {
        context.env = newEnv;
        return true;
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        Source source = request.getSource();
        return UpstreamRootGen.create(this, BytecodeConfig.WITH_SOURCE, b -> {
            b.beginRoot();
            b.beginSource(source);
            b.beginSourceSection(0, source.getLength());
            b.beginTag(StandardTags.StatementTag.class);
            b.beginReturn();
            int depth = 0;
            while (depth < source.getLength() && source.getCharacters().charAt(depth) == '(') {
                depth++;
            }
            for (int i = 0; i < depth; i++) {
                b.beginSourceSection(i, source.getLength() - 2 * i);
                b.beginTag(StandardTags.ExpressionTag.class);
                b.beginIdentity();
            }
            b.emitLoadConstant(42);
            for (int i = 0; i < depth; i++) {
                b.endIdentity();
                b.endTag(StandardTags.ExpressionTag.class);
                b.endSourceSection();
            }
            b.endReturn();
            b.endTag(StandardTags.StatementTag.class);
            b.endSourceSection();
            b.endSource();
            b.endRoot();
        }).getNode(0).getCallTarget();
    }
}
