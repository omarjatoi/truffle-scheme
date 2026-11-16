package scheme.test;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.EventContext;
import com.oracle.truffle.api.instrumentation.ExecutionEventListener;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.TruffleInstrument;
import com.oracle.truffle.api.source.SourceSection;

/**
 * A Truffle instrument for NumbersTest: keeps the raw guest value of the last statement in a source
 * named {@link #SOURCE}, so a test can look at the runtime object behind it (for example a
 * primitive's node tree) without the polyglot API wrapping it.
 */
@TruffleInstrument.Registration(id = ResultProbe.ID, name = "Scheme test result probe", services = ResultProbe.class)
public final class ResultProbe extends TruffleInstrument {

    public static final String ID = "scheme-test-result-probe";
    public static final String SOURCE = "probe.scm";

    public volatile Object last;

    @Override
    protected void onCreate(Env env) {
        env.registerService(this);
        SourceSectionFilter filter = SourceSectionFilter.newBuilder().tagIs(StandardTags.StatementTag.class).build();
        env.getInstrumenter().attachExecutionEventListener(filter, new ExecutionEventListener() {
            @Override
            public void onEnter(EventContext context, VirtualFrame frame) {
            }

            @Override
            public void onReturnValue(EventContext context, VirtualFrame frame, Object result) {
                SourceSection s = context.getInstrumentedSourceSection();
                if (s != null && SOURCE.equals(s.getSource().getName())) {
                    last = result;
                }
            }

            @Override
            public void onReturnExceptional(EventContext context, VirtualFrame frame, Throwable exception) {
            }
        });
    }
}
