package scheme.test;

import java.util.ArrayList;
import java.util.List;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.EventContext;
import com.oracle.truffle.api.instrumentation.ExecutionEventListener;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.TruffleInstrument;
import com.oracle.truffle.api.source.SourceSection;

/**
 * A Truffle instrument for ToolsTest (M3-10, design.md D13): records the statement and call events
 * Scheme code produces, each as "tag line:column text".
 */
@TruffleInstrument.Registration(id = EventRecorder.ID, name = "Scheme test event recorder", services = EventRecorder.class)
public final class EventRecorder extends TruffleInstrument {

    public static final String ID = "scheme-test-event-recorder";

    public final List<String> events = new ArrayList<>();

    @Override
    protected void onCreate(Env env) {
        env.registerService(this);
        SourceSectionFilter filter = SourceSectionFilter.newBuilder().tagIs(StandardTags.StatementTag.class, StandardTags.CallTag.class).build();
        env.getInstrumenter().attachExecutionEventListener(filter, new ExecutionEventListener() {
            @Override
            public void onEnter(EventContext context, VirtualFrame frame) {
                SourceSection s = context.getInstrumentedSourceSection();
                if (s != null) {
                    record(context, s);
                }
            }

            @Override
            public void onReturnValue(EventContext context, VirtualFrame frame, Object result) {
            }

            @Override
            public void onReturnExceptional(EventContext context, VirtualFrame frame, Throwable exception) {
            }
        });
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private void record(EventContext context, SourceSection s) {
        String at = s.getSource().getName() + " " + s.getStartLine() + ":" + s.getStartColumn() + " " + s.getCharacters();
        if (context.hasTag(StandardTags.StatementTag.class)) {
            events.add("statement " + at);
        }
        if (context.hasTag(StandardTags.CallTag.class)) {
            events.add("call " + at);
        }
    }
}
