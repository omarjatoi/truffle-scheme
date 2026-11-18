package scheme.test;

import java.util.ArrayList;
import java.util.List;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.EventContext;
import com.oracle.truffle.api.instrumentation.ExecutionEventListener;
import com.oracle.truffle.api.instrumentation.LoadSourceSectionEvent;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.TruffleInstrument;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.NodeLibrary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;

/**
 * A Truffle instrument for ToolsTest (M3-10, design.md D13). It records:
 * <ul>
 * <li>{@link #events}: the root, statement and call events Scheme code produces, each as
 * "tag file line:column text", and counts the statements and calls without a source section
 * ({@link #sourceless});
 * <li>{@link #loaded}: the root and statement nodes of non-internal sources as they load, as the
 * coverage tool sees them ({@code CoverageTracker} groups a source's statements by their root's
 * source section): "tag file line:column text in name@line:column", or "in name@none" for a root
 * without a section;
 * <li>{@link #bodies}: on entering each root body ({@code RootBodyTag}), the procedure's name and
 * the variables its scope shows then, which Truffle says are initialized by then.
 * </ul>
 */
@TruffleInstrument.Registration(id = EventRecorder.ID, name = "Scheme test event recorder", services = EventRecorder.class)
public final class EventRecorder extends TruffleInstrument {

    public static final String ID = "scheme-test-event-recorder";

    public final List<String> events = new ArrayList<>();
    public final List<String> loaded = new ArrayList<>();
    public final List<String> bodies = new ArrayList<>();
    /** Statement and call events at nodes without a source section. */
    public volatile int sourceless;

    @Override
    protected void onCreate(Env env) {
        env.registerService(this);
        SourceSectionFilter filter = SourceSectionFilter.newBuilder().tagIs(StandardTags.RootTag.class, StandardTags.StatementTag.class, StandardTags.CallTag.class).build();
        env.getInstrumenter().attachExecutionEventListener(filter, new ExecutionEventListener() {
            @Override
            public void onEnter(EventContext context, VirtualFrame frame) {
                SourceSection s = context.getInstrumentedSourceSection();
                if (s != null) {
                    record(context, s);
                } else if (context.hasTag(StandardTags.StatementTag.class) || context.hasTag(StandardTags.CallTag.class)) {
                    sourceless++;
                }
            }

            @Override
            public void onReturnValue(EventContext context, VirtualFrame frame, Object result) {
            }

            @Override
            public void onReturnExceptional(EventContext context, VirtualFrame frame, Throwable exception) {
            }
        });
        SourceSectionFilter roots = SourceSectionFilter.newBuilder().tagIs(StandardTags.RootTag.class, StandardTags.StatementTag.class).includeInternal(false).build();
        env.getInstrumenter().attachLoadSourceSectionListener(roots, this::load, true);
        SourceSectionFilter bodyFilter = SourceSectionFilter.newBuilder().tagIs(StandardTags.RootBodyTag.class).includeInternal(false).build();
        env.getInstrumenter().attachExecutionEventListener(bodyFilter, new ExecutionEventListener() {
            @Override
            public void onEnter(EventContext context, VirtualFrame frame) {
                body(context.getInstrumentedNode(), frame.materialize());
            }

            @Override
            public void onReturnValue(EventContext context, VirtualFrame frame, Object result) {
            }

            @Override
            public void onReturnExceptional(EventContext context, VirtualFrame frame, Throwable exception) {
            }
        });
    }

    @TruffleBoundary
    private void record(EventContext context, SourceSection s) {
        String at = at(s);
        if (context.hasTag(StandardTags.RootTag.class)) {
            events.add("root " + at);
        }
        if (context.hasTag(StandardTags.StatementTag.class)) {
            events.add("statement " + at);
        }
        if (context.hasTag(StandardTags.CallTag.class)) {
            events.add("call " + at);
        }
    }

    private static String at(SourceSection s) {
        return s.getSource().getName() + " " + s.getStartLine() + ":" + s.getStartColumn() + " " + s.getCharacters();
    }

    private synchronized void load(LoadSourceSectionEvent event) {
        Node node = event.getNode();
        RootNode root = node.getRootNode();
        SourceSection rootSection = root.getSourceSection();
        String tag = ((com.oracle.truffle.api.instrumentation.InstrumentableNode) node).hasTag(StandardTags.RootTag.class) ? "root " : "statement ";
        loaded.add(tag + at(event.getSourceSection()) + " in " + root.getName() + "@" + (rootSection == null ? "none" : rootSection.getStartLine() + ":" + rootSection.getStartColumn()));
    }

    @TruffleBoundary
    private synchronized void body(Node node, com.oracle.truffle.api.frame.MaterializedFrame frame) {
        StringBuilder sb = new StringBuilder(node.getRootNode().getName()).append(' ');
        List<String> vars = new ArrayList<>();
        try {
            Object scope = NodeLibrary.getUncached().getScope(node, frame, true);
            InteropLibrary interop = InteropLibrary.getUncached();
            Object members = interop.getMembers(scope);
            for (long i = 0; i < interop.getArraySize(members); i++) {
                String name = interop.asString(interop.readArrayElement(members, i));
                vars.add(name + "=" + interop.asString(interop.toDisplayString(interop.readMember(scope, name))));
            }
        } catch (InteropException e) {
            vars.add(e.toString());
        }
        bodies.add(sb.append(vars).toString());
    }
}
