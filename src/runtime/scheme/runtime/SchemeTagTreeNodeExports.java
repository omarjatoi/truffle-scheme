package scheme.runtime;

import com.oracle.truffle.api.bytecode.TagTreeNode;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.interop.NodeLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/**
 * What instruments see of a Scheme procedure at an instrumented node (the Bytecode DSL's tag tree
 * nodes, design.md D13): its local scope is a {@link FrameScope}, Scheme's variables as Scheme sees
 * them, instead of the Bytecode DSL's default scope of raw frame slots (compiler temporaries, boxes
 * and all, and no captured variables). Only tools ask for it (plan.md M6-05).
 */
@ExportLibrary(value = NodeLibrary.class, receiverType = TagTreeNode.class)
@SuppressWarnings("unused")
final class SchemeTagTreeNodeExports {

    private SchemeTagTreeNodeExports() {
    }

    @ExportMessage
    static boolean hasScope(TagTreeNode node, Frame frame) {
        return true;
    }

    @ExportMessage
    static Object getScope(TagTreeNode node, Frame frame, boolean nodeEnter) throws UnsupportedMessageException {
        Object scope = FrameScope.create(node, frame == null ? null : frame.materialize(), nodeEnter);
        if (scope == null) {
            throw UnsupportedMessageException.create();
        }
        return scope;
    }
}
