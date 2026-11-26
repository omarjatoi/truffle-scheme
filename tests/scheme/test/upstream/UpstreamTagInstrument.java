package scheme.test.upstream;

import java.util.concurrent.atomic.AtomicInteger;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.EventBinding;
import com.oracle.truffle.api.instrumentation.EventContext;
import com.oracle.truffle.api.instrumentation.ExecutionEventListener;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter;
import com.oracle.truffle.api.instrumentation.TruffleInstrument;

/**
 * An instrument for reproducing Truffle bugs (docs/upstream.md): a test looks it up and attaches a
 * listener whose filter names the tags it chooses, as a tool's instrument does
 * (UpstreamUnprovidedTagTest). The listener counts the tagged nodes it enters.
 */
@TruffleInstrument.Registration(id = UpstreamTagInstrument.ID, name = "Upstream reproducers' tag filter", services = UpstreamTagInstrument.class)
public final class UpstreamTagInstrument extends TruffleInstrument {

    public static final String ID = "upstream-repro-tags";

    /** How many tagged nodes the listeners have entered. */
    public final AtomicInteger entered = new AtomicInteger();

    private Env env;

    @Override
    protected void onCreate(Env instrumentEnv) {
        this.env = instrumentEnv;
        instrumentEnv.registerService(this);
    }

    /** Attaches a listener to the nodes tagged with one of {@code tags}, in every language. */
    public EventBinding<?> attach(Class<?>... tags) {
        SourceSectionFilter filter = SourceSectionFilter.newBuilder().tagIs(tags).build();
        return env.getInstrumenter().attachExecutionEventListener(filter, new ExecutionEventListener() {
            @Override
            public void onEnter(EventContext context, VirtualFrame frame) {
                entered.incrementAndGet();
            }

            @Override
            public void onReturnValue(EventContext context, VirtualFrame frame, Object result) {
            }

            @Override
            public void onReturnExceptional(EventContext context, VirtualFrame frame, Throwable exception) {
            }
        });
    }
}
