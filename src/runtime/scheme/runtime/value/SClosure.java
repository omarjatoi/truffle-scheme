package scheme.runtime.value;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;

import scheme.runtime.InteropValues;
import scheme.runtime.SchemeLanguage;
import scheme.runtime.SchemeRootNode;

/** A procedure: a flat closure (design.md D2) over a Bytecode DSL root. */
@ExportLibrary(InteropLibrary.class)
public final class SClosure implements TruffleObject {

    public final RootCallTarget callTarget;

    /**
     * The captured values, never written once the closure exists: a variable that {@code set!}
     * assigns is captured as its {@code Box} (design.md D2). So when the closure is a compilation
     * constant (a global procedure under its cell's assumption, a closure inlined with the call that
     * made it), reading a captured value folds to a constant too (plan.md M5-10). The one write
     * after construction, {@code PatchCaptured} (Fixing Letrec, M5-05), happens before anything can
     * read the slot.
     */
    @CompilationFinal(dimensions = 1) public final Object[] captured;

    public SClosure(RootCallTarget callTarget, Object[] captured) {
        this.callTarget = callTarget;
        this.captured = captured;
    }

    /**
     * The procedure's name, or null if it is an anonymous lambda: what {@code write} shows. Tools
     * name an anonymous lambda's frames by its location instead ({@link SchemeRootNode#getName}).
     * Behind a boundary: it asks the root node, and a root's {@code getName} (an OSR root's formats
     * a string) is not for partial evaluation.
     */
    @TruffleBoundary
    public String getName() {
        RootNode root = callTarget.getRootNode();
        return root instanceof SchemeRootNode scheme ? scheme.getProcedureName() : root.getName();
    }

    // An executable to interop (design.md D10): executing it runs it under a root prompt of its own
    // (D6, InteropCallRootNode), with the arguments made Scheme values (InteropValues.toScheme).

    @ExportMessage
    boolean isExecutable() {
        return true;
    }

    @ExportMessage
    Object execute(Object[] arguments,
                    @Bind Node node,
                    @Cached IndirectCallNode callNode) throws UnsupportedTypeException {
        Object[] all = new Object[arguments.length + 1];
        all[0] = this;
        for (int i = 0; i < arguments.length; i++) {
            all[i + 1] = InteropValues.toScheme(arguments[i]);
        }
        return callNode.call(SchemeLanguage.get(node).interopCallTarget(), all);
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

    // Interop (design.md D10).

    @ExportMessage
    boolean hasLanguageId() {
        return true;
    }

    @ExportMessage
    String getLanguageId() {
        return SchemeLanguage.ID;
    }

    @ExportMessage
    boolean hasMetaObject() {
        return true;
    }

    @ExportMessage
    Object getMetaObject() {
        return SchemeType.PROCEDURE;
    }

    @ExportMessage
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return InteropValues.display(this);
    }
}
