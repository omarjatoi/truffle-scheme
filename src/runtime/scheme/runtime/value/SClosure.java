package scheme.runtime.value;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.SourceSection;

import scheme.runtime.InteropValues;
import scheme.runtime.SchemeLanguage;

/**
 * A procedure: a flat closure (design.md D2) over a Bytecode DSL root.
 *
 * <p>EXPLORATION: through interop it is executable (design.md D10). Executing it runs it under a
 * root prompt of its own ({@code InteropCallRootNode}), so it never hands a {@code TailCall} or a
 * {@code Capture} to the caller.
 */
@ExportLibrary(InteropLibrary.class)
public final class SClosure implements TruffleObject {

    public final RootCallTarget callTarget;
    public final Object[] captured;

    public SClosure(RootCallTarget callTarget, Object[] captured) {
        this.callTarget = callTarget;
        this.captured = captured;
    }

    public String getName() {
        return callTarget.getRootNode().getName();
    }

    @ExportMessage
    boolean isExecutable() {
        return true;
    }

    @ExportMessage
    Object execute(Object[] arguments,
                    @Bind Node node,
                    @Cached IndirectCallNode callNode) {
        return callNode.call(SchemeLanguage.get(node).interopCallTarget(), withProcedure(arguments));
    }

    private Object[] withProcedure(Object[] arguments) {
        Object[] all = new Object[arguments.length + 1];
        all[0] = this;
        for (int i = 0; i < arguments.length; i++) {
            all[i + 1] = InteropValues.toScheme(arguments[i]);
        }
        return all;
    }

    @ExportMessage
    boolean hasExecutableName() {
        return getName() != null;
    }

    @ExportMessage
    Object getExecutableName() throws UnsupportedMessageException {
        String name = getName();
        if (name == null) {
            throw UnsupportedMessageException.create();
        }
        return name;
    }

    @ExportMessage
    @TruffleBoundary
    boolean hasSourceLocation() {
        return callTarget.getRootNode().getSourceSection() != null;
    }

    @ExportMessage
    @TruffleBoundary
    SourceSection getSourceLocation() throws UnsupportedMessageException {
        SourceSection section = callTarget.getRootNode().getSourceSection();
        if (section == null) {
            throw UnsupportedMessageException.create();
        }
        return section;
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
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return InteropValues.display(this);
    }
}
