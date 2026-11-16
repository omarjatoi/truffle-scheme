package scheme.runtime;

import java.util.List;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import scheme.runtime.value.SString;
import scheme.runtime.value.SSymbol;
import scheme.runtime.value.SVector;

/**
 * EXPLORATION (docs/interop-and-tools-exploration.md): the language's top scope
 * ({@link SchemeLanguage#getScope}): the interaction environment's variables (design.md D15), as
 * members. Embedders read them with {@code context.getBindings("scheme").getMember("car")}, and
 * define new ones with {@code putMember}, as {@code (define name value)} at the REPL would. The
 * debugger shows it as the global scope.
 */
@ExportLibrary(InteropLibrary.class)
final class TopScope implements TruffleObject {

    private final SchemeContext context;

    TopScope(SchemeContext context) {
        this.context = context;
    }

    private SchemeCompiler compiler() {
        return SchemeLanguage.get(null).compiler();
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
        List<SSymbol> names = compiler().interactionVariables(context);
        Object[] strings = new Object[names.size()];
        for (int i = 0; i < strings.length; i++) {
            strings[i] = SString.of(names.get(i).toString());
        }
        return new SVector(strings, true);
    }

    @ExportMessage
    @TruffleBoundary
    boolean isMemberReadable(String member) {
        GlobalCell cell = compiler().interactionCell(context, context.symbols.intern(member));
        return cell != null && cell.valueOrNull() != null;
    }

    @ExportMessage
    @TruffleBoundary
    Object readMember(String member) throws UnknownIdentifierException {
        GlobalCell cell = compiler().interactionCell(context, context.symbols.intern(member));
        Object value = cell == null ? null : cell.valueOrNull();
        if (value == null) {
            throw UnknownIdentifierException.create(member);
        }
        return value;
    }

    /** The interaction environment's own cell for the name, or null if it is imported or syntax. */
    private GlobalCell ownCell(String member) {
        GlobalCell cell = compiler().interactionCell(context, context.symbols.intern(member));
        return cell != null && compiler().interactionNamespace(context).owns(cell) ? cell : null;
    }

    @ExportMessage
    @TruffleBoundary
    boolean isMemberModifiable(String member) {
        GlobalCell cell = ownCell(member);
        return cell != null && cell.valueOrNull() != null;
    }

    @ExportMessage
    @TruffleBoundary
    boolean isMemberInsertable(String member) {
        GlobalCell cell = ownCell(member);
        return cell != null && cell.valueOrNull() == null;
    }

    /** As {@code (define member value)} in the interaction environment. Imported bindings can't be assigned (design.md D7). */
    @ExportMessage
    @TruffleBoundary
    void writeMember(String member, Object value) throws UnsupportedMessageException {
        GlobalCell cell = ownCell(member);
        if (cell == null) {
            throw UnsupportedMessageException.create();
        }
        cell.define(InteropValues.toScheme(value));
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
        return "interaction-environment";
    }
}
