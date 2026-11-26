package scheme.test;

import java.util.ArrayList;
import java.util.List;

import com.oracle.truffle.api.instrumentation.InstrumentableNode;
import com.oracle.truffle.api.instrumentation.LoadSourceSectionEvent;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.TruffleInstrument;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;

@TruffleInstrument.Registration(id = SectionRecorder.ID, name = "Scheme test section recorder", services = SectionRecorder.class)
public final class SectionRecorder extends TruffleInstrument {

    public static final String ID = "scheme-test-section-recorder";

    public final List<String> loaded = new ArrayList<>();

    @Override
    protected void onCreate(Env env) {
        env.registerService(this);
        SourceSectionFilter filter = SourceSectionFilter.newBuilder().tagIs(StandardTags.RootTag.class, StandardTags.StatementTag.class, StandardTags.CallTag.class, StandardTags.ExpressionTag.class,
                        StandardTags.ReadVariableTag.class, StandardTags.WriteVariableTag.class).build();
        env.getInstrumenter().attachLoadSourceSectionListener(filter, this::load, true);
    }

    private static String at(SourceSection s) {
        if (s == null) {
            return "none";
        }
        String text = s.getCharacters().toString().replace('\n', ' ');
        if (text.length() > 30) {
            text = text.substring(0, 30) + "...";
        }
        return s.getSource().getName() + ":" + s.getStartLine() + ":" + s.getStartColumn() + " `" + text + "`";
    }

    private synchronized void load(LoadSourceSectionEvent event) {
        Node node = event.getNode();
        RootNode root = node.getRootNode();
        InstrumentableNode n = (InstrumentableNode) node;
        StringBuilder tags = new StringBuilder();
        for (Class<?> t : List.of(StandardTags.RootTag.class, StandardTags.StatementTag.class, StandardTags.CallTag.class, StandardTags.ExpressionTag.class, StandardTags.ReadVariableTag.class,
                        StandardTags.WriteVariableTag.class)) {
            @SuppressWarnings("unchecked")
            Class<? extends com.oracle.truffle.api.instrumentation.Tag> tag = (Class<? extends com.oracle.truffle.api.instrumentation.Tag>) t;
            if (n.hasTag(tag)) {
                tags.append(t.getSimpleName().replace("Tag", "")).append(',');
            }
        }
        loaded.add(tags + " " + at(event.getSourceSection()) + " in " + root.getName() + "@" + at(root.getSourceSection()));
    }
}
