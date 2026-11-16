package scheme.runtime;

import java.util.ArrayList;
import java.util.List;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import scheme.runtime.value.SString;
import scheme.runtime.value.SSymbol;
import scheme.runtime.value.SVector;

/**
 * The language's top scope ({@link SchemeLanguage#getScope}, {@code context.getBindings("scheme")},
 * design.md D10): the interaction environment's variables (D15), imported or defined, as members.
 * Syntactic keywords are not members. {@code putMember} is {@code (define name value)} at the
 * interaction environment's top level: it defines a new variable, redefines one, or shadows an
 * import, as the REPL would; a keyword can't be redefined this way. Names starting with {@code %}
 * (the runtime's internals) are left out of the member list unless internal members are asked for.
 */
@ExportLibrary(InteropLibrary.class)
final class TopScope implements TruffleObject {

    private final SchemeContext context;
    private final SchemeCompiler compiler;

    TopScope(SchemeContext context, SchemeCompiler compiler) {
        this.context = context;
        this.compiler = compiler;
    }

    /** The cell the member names at top level, or null if it is a keyword. */
    private GlobalCell cell(String member) {
        return compiler.interactionCell(context, context.symbols.intern(member));
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
    Object getMembers(boolean includeInternal) {
        List<Object> names = new ArrayList<>();
        for (SSymbol name : compiler.interactionVariables(context)) {
            String text = name.toString();
            if (includeInternal || !text.startsWith("%")) {
                names.add(SString.of(text));
            }
        }
        return new SVector(names.toArray(), true);
    }

    @ExportMessage
    @TruffleBoundary
    boolean isMemberReadable(String member) {
        GlobalCell cell = cell(member);
        return cell != null && cell.valueOrNull() != null;
    }

    @ExportMessage
    @TruffleBoundary
    Object readMember(String member) throws UnknownIdentifierException {
        GlobalCell cell = cell(member);
        Object value = cell == null ? null : cell.valueOrNull();
        if (value == null) {
            throw UnknownIdentifierException.create(member);
        }
        return value;
    }

    @ExportMessage
    @TruffleBoundary
    boolean isMemberModifiable(String member) {
        return isMemberReadable(member);
    }

    @ExportMessage
    @TruffleBoundary
    boolean isMemberInsertable(String member) {
        GlobalCell cell = cell(member);
        return cell != null && cell.valueOrNull() == null;
    }

    @ExportMessage
    @TruffleBoundary
    void writeMember(String member, Object value) throws UnsupportedMessageException, UnsupportedTypeException {
        if (!compiler.interactionDefine(context, context.symbols.intern(member), InteropValues.toScheme(value))) {
            throw UnsupportedMessageException.create();
        }
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
        return "#<environment interaction-environment>";
    }
}
