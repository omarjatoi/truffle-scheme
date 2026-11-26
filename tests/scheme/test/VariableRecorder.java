package scheme.test;

import java.util.ArrayList;
import java.util.List;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.EventContext;
import com.oracle.truffle.api.instrumentation.ExecutionEventListener;
import com.oracle.truffle.api.instrumentation.InstrumentableNode;
import com.oracle.truffle.api.instrumentation.LoadSourceSectionEvent;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter;
import com.oracle.truffle.api.instrumentation.StandardTags.ReadVariableTag;
import com.oracle.truffle.api.instrumentation.StandardTags.WriteVariableTag;
import com.oracle.truffle.api.instrumentation.TruffleInstrument;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.source.SourceSection;

/**
 * A Truffle instrument for ToolsTest (M13-14, design.md D13 "Variables' reads and writes"): what a
 * tool sees of Scheme's variables through {@code ReadVariableTag} and {@code WriteVariableTag},
 * as Insight's and the Language Server's do. It records:
 * <ul>
 * <li>{@link #events}: each read and write that runs in a non-internal source, with the name its
 * node object gives ({@code readVariableName}, {@code writeVariableName}), where it is, and the
 * value a read returns: "read name line:column text = value", "write name line:column text";
 * <li>{@link #loaded}: each such node as it loads, before it runs, as the Language Server sees
 * them: "read name line:column text", "write name line:column text".
 * </ul>
 */
@TruffleInstrument.Registration(id = VariableRecorder.ID, name = "Scheme test variable recorder", services = VariableRecorder.class)
public final class VariableRecorder extends TruffleInstrument {

    public static final String ID = "scheme-test-variable-recorder";

    public final List<String> events = new ArrayList<>();
    public final List<String> loaded = new ArrayList<>();

    @Override
    protected void onCreate(Env env) {
        env.registerService(this);
        SourceSectionFilter filter = SourceSectionFilter.newBuilder().tagIs(ReadVariableTag.class, WriteVariableTag.class).includeInternal(false).build();
        env.getInstrumenter().attachExecutionEventListener(filter, new ExecutionEventListener() {
            @Override
            public void onEnter(EventContext context, VirtualFrame frame) {
            }

            @Override
            public void onReturnValue(EventContext context, VirtualFrame frame, Object result) {
                record(context, result);
            }

            @Override
            public void onReturnExceptional(EventContext context, VirtualFrame frame, Throwable exception) {
            }

            // A write whose value's call suspends the frame (a capture, a spill, torture) yields, and
            // is resumed: neither is the write's return.
            @Override
            public void onYield(EventContext context, VirtualFrame frame, Object value) {
            }

            @Override
            public void onResume(EventContext context, VirtualFrame frame) {
            }
        });
        env.getInstrumenter().attachLoadSourceSectionListener(filter, this::load, true);
    }

    @TruffleBoundary
    private synchronized void record(EventContext context, Object result) {
        String event = describe(context.getNodeObject(), context.hasTag(ReadVariableTag.class), context.getInstrumentedSourceSection());
        if (context.hasTag(ReadVariableTag.class)) {
            InteropLibrary interop = InteropLibrary.getUncached();
            try {
                event += " = " + interop.asString(interop.toDisplayString(result));
            } catch (InteropException e) {
                event += " = <" + e + ">";
            }
        }
        events.add(event);
    }

    private synchronized void load(LoadSourceSectionEvent event) {
        InstrumentableNode node = (InstrumentableNode) event.getNode();
        loaded.add(describe(node.getNodeObject(), node.hasTag(ReadVariableTag.class), event.getSourceSection()));
    }

    private static String describe(Object nodeObject, boolean read, SourceSection s) {
        String member = read ? ReadVariableTag.NAME : WriteVariableTag.NAME;
        String name;
        try {
            InteropLibrary interop = InteropLibrary.getUncached();
            name = interop.asString(interop.readMember(nodeObject, member));
        } catch (InteropException | RuntimeException e) {
            name = "<" + e + ">";
        }
        String at = s == null ? "<no section>" : s.getStartLine() + ":" + s.getStartColumn() + " " + s.getCharacters();
        return (read ? "read " : "write ") + name + " " + at;
    }
}
