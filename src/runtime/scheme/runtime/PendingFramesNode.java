package scheme.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.GenerateWrapper;
import com.oracle.truffle.api.instrumentation.InstrumentableNode;
import com.oracle.truffle.api.instrumentation.ProbeNode;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

/**
 * The frames a prompt's driver holds on the heap ({@link PromptDriver}), as an instrument that
 * counts frames sees them (design.md D10, "Sandboxing"): {@code sandbox.MaxStackFrames} counts
 * the enter and return events of {@code RootTag} nodes, and a Scheme frame
 * that suspends reports a yield, as the Bytecode DSL reports one for every {@code @Yield}: to
 * Truffle it has left the stack. A frame a recursion spilled, or one of a continuation the program
 * goes on with, is still one of the program's frames, though. So while a driver holds a suspended
 * frame, it reports an enter of this node, and a return when it resumes or drops the frame: the
 * frames an instrument counts are the program's, whether on the Java stack or on the heap.
 *
 * <p>Its source is internal, so the tools that leave out internal code (the debugger, the CPU
 * sampler and tracer, coverage) never see it; the sandbox, which counts every frame, does. Only
 * an instrument that asks for RootTag nodes in internal code wraps it ({@link #createWrapper}):
 * otherwise {@link #probe} is null, and the driver reports nothing, at no cost. Its execute method
 * is never called: the driver reports the events itself, through the wrapper's probe.
 */
@GenerateWrapper
public class PendingFramesNode extends Node implements InstrumentableNode {

    PendingFramesNode() {
    }

    /** Never called (see the class comment): a wrapper needs an execute method to wrap. */
    public void execute(@SuppressWarnings("unused") VirtualFrame frame) {
    }

    @Override
    public final boolean isInstrumentable() {
        return true;
    }

    @Override
    public final boolean hasTag(Class<? extends Tag> tag) {
        return tag == StandardTags.RootTag.class;
    }

    @Override
    public final SourceSection getSourceSection() {
        return Section.SECTION;
    }

    @Override
    public final WrapperNode createWrapper(ProbeNode probe) {
        return new PendingFramesNodeWrapper(this, probe);
    }

    /**
     * The probe of the instruments that listen to {@code node}, a driver's child: the wrapper's,
     * once an instrument has wrapped it, or null. A constant in compiled code.
     */
    static ProbeNode probe(PendingFramesNode node) {
        return node instanceof WrapperNode wrapper ? wrapper.getProbeNode() : null;
    }

    /** The node's source section, internal: made the first time an instrument asks for it. */
    private static final class Section {
        static final SourceSection SECTION = Source.newBuilder(SchemeLanguage.ID, "", "<pending frames>").internal(true).build().createUnavailableSection();
    }
}
