package scheme.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.SourceSection;

import scheme.runtime.value.SConstant;
import scheme.runtime.value.SString;
import scheme.runtime.value.SVector;

/**
 * A Scheme procedure's local scope as tools see it ({@link SchemeTagTreeNodeExports}, plan.md
 * M6-05): its members are the {@link FrameVariables} at the node, with their values. A variable
 * that the program assigns is a {@link Box} (design.md D2), and writing it sets the box; every other
 * variable is immutable, as Scheme sees it (writing one isn't supported: a frame slot or captured
 * value is a copy that closures and continuation segments may share). The scope has no parent: the
 * debugger shows the language's top scope ({@link TopScope}) as the global one by itself.
 */
@ExportLibrary(InteropLibrary.class)
final class FrameScope implements TruffleObject {

    private final Node node;
    private final FrameVariables variables;
    /** The procedure's own frame, or null for a scope without values. */
    private final MaterializedFrame frame;

    private FrameScope(Node node, FrameVariables variables, MaterializedFrame frame) {
        this.node = node;
        this.variables = variables;
        this.frame = frame;
    }

    /** The scope at the node, or null if it isn't in Scheme code. */
    @TruffleBoundary
    static FrameScope create(Node node, MaterializedFrame frame, boolean enter) {
        FrameVariables variables = FrameVariables.at(node, frame, enter);
        return variables == null ? null : new FrameScope(node, variables, FrameVariables.resolve(variables.bytecode, frame));
    }

    @ExportMessage
    boolean isScope() {
        return true;
    }

    @ExportMessage
    boolean hasMembers() {
        return true;
    }

    @ExportMessage
    @TruffleBoundary
    Object getMembers(@SuppressWarnings("unused") boolean includeInternal) {
        Object[] names = new Object[variables.names.length];
        for (int k = 0; k < names.length; k++) {
            names[k] = SString.of(variables.names[k]);
        }
        return new SVector(names, true);
    }

    @ExportMessage
    @TruffleBoundary
    boolean isMemberReadable(String member) {
        return variables.indexOf(member) >= 0;
    }

    @ExportMessage
    @TruffleBoundary
    Object readMember(String member) throws UnknownIdentifierException {
        int k = variables.indexOf(member);
        if (k < 0) {
            throw UnknownIdentifierException.create(member);
        }
        Object value = FrameVariables.value(variables.raw(frame, k));
        // Without a frame (a static scope), or for a variable not set yet: nothing to show.
        return value == null ? SConstant.UNSPECIFIED : value;
    }

    @ExportMessage
    @TruffleBoundary
    boolean isMemberModifiable(String member) {
        int k = variables.indexOf(member);
        return k >= 0 && variables.raw(frame, k) instanceof Box;
    }

    @ExportMessage
    boolean isMemberInsertable(@SuppressWarnings("unused") String member) {
        return false;
    }

    @ExportMessage
    @TruffleBoundary
    void writeMember(String member, Object value) throws UnsupportedMessageException, UnsupportedTypeException {
        int k = variables.indexOf(member);
        if (k < 0 || !(variables.raw(frame, k) instanceof Box box)) {
            throw UnsupportedMessageException.create();
        }
        box.value = InteropValues.toScheme(value);
    }

    @ExportMessage
    boolean hasLanguageId() {
        return true;
    }

    @ExportMessage
    String getLanguageId() {
        return SchemeLanguage.ID;
    }

    @ExportMessage
    @TruffleBoundary
    boolean hasSourceLocation() {
        return node.getSourceSection() != null;
    }

    @ExportMessage
    @TruffleBoundary
    SourceSection getSourceLocation() throws UnsupportedMessageException {
        SourceSection section = node.getSourceSection();
        if (section == null) {
            throw UnsupportedMessageException.create();
        }
        return section;
    }

    @ExportMessage
    @TruffleBoundary
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return variables.bytecode.getRootNode().getName();
    }
}
