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
 * whatever its source text, a program is one statement that returns 42. Its roots are
 * {@link UpstreamRoot}s.
 */
// The debugger asks every language for AlwaysHalt.
@ProvidedTags({DebuggerTags.AlwaysHalt.class, StandardTags.RootTag.class, StandardTags.RootBodyTag.class, StandardTags.StatementTag.class})
@TruffleLanguage.Registration(id = UpstreamLanguage.ID, name = "Upstream reproducers")
public final class UpstreamLanguage extends TruffleLanguage<Object> {

    public static final String ID = "upstream-repro";

    @Override
    protected Object createContext(Env env) {
        return new Object();
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
            b.emitLoadConstant(42);
            b.endReturn();
            b.endTag(StandardTags.StatementTag.class);
            b.endSourceSection();
            b.endSource();
            b.endRoot();
        }).getNode(0).getCallTarget();
    }
}
