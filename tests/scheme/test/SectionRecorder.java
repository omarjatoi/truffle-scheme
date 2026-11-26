package scheme.test;

import java.util.ArrayList;
import java.util.List;

import com.oracle.truffle.api.instrumentation.InstrumentableNode;
import com.oracle.truffle.api.instrumentation.LoadSourceSectionEvent;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.instrumentation.TruffleInstrument;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;

/**
 * A Truffle instrument for SourceLocationTest (plan.md M14-05, design.md D13): every node with a
 * standard tag as it loads, internal or not, with its source section and its root's.
 */
@TruffleInstrument.Registration(id = SectionRecorder.ID, name = "Scheme test section recorder", services = SectionRecorder.class)
public final class SectionRecorder extends TruffleInstrument {

    public static final String ID = "scheme-test-section-recorder";

    private static final List<Class<? extends Tag>> TAGS = List.of(StandardTags.RootTag.class, StandardTags.StatementTag.class, StandardTags.CallTag.class,
                    StandardTags.ExpressionTag.class, StandardTags.ReadVariableTag.class, StandardTags.WriteVariableTag.class);

    /** A tagged node: "file:line:column", its tags' names, its root's name and "file:line:column". */
    public record Loaded(String at, String tags, String root, String rootAt) {
    }

    public final List<Loaded> loaded = new ArrayList<>();

    @Override
    protected void onCreate(Env env) {
        env.registerService(this);
        SourceSectionFilter filter = SourceSectionFilter.newBuilder().tagIs(TAGS.toArray(new Class<?>[0])).build();
        env.getInstrumenter().attachLoadSourceSectionListener(filter, this::load, true);
    }

    private static String at(SourceSection s) {
        return s == null ? "none" : s.getSource().getName() + ":" + s.getStartLine() + ":" + s.getStartColumn();
    }

    private synchronized void load(LoadSourceSectionEvent event) {
        Node node = event.getNode();
        RootNode root = node.getRootNode();
        List<String> tags = new ArrayList<>();
        for (Class<? extends Tag> tag : TAGS) {
            if (((InstrumentableNode) node).hasTag(tag)) {
                tags.add(tag.getSimpleName());
            }
        }
        loaded.add(new Loaded(at(event.getSourceSection()), String.join(",", tags), root.getName(), at(root.getSourceSection())));
    }
}
