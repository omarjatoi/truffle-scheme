package scheme.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/**
 * EXPLORATION (docs/interop-and-tools-exploration.md): how tools (the debugger's variables view)
 * show a primitive or foreign value in Scheme terms ({@link SchemeLanguage#getLanguageView}):
 * {@code #t} rather than {@code true}, {@code +inf.0} rather than {@code Infinity}. Every other
 * message goes to the value itself.
 */
@ExportLibrary(value = InteropLibrary.class, delegateTo = "delegate")
final class LanguageView implements TruffleObject {

    final Object delegate;

    LanguageView(Object delegate) {
        this.delegate = delegate;
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
    Object toDisplayString(boolean allowSideEffects, @CachedLibrary("this.delegate") InteropLibrary interop) {
        if (delegate instanceof Long || delegate instanceof Double || delegate instanceof Boolean) {
            return InteropValues.display(delegate);
        }
        if (interop.isNull(delegate)) {
            // A local the code hasn't stored to yet (DefaultBytecodeScope's null), or a foreign null.
            return "#<unassigned>";
        }
        return InteropValues.displayForeign(interop.toDisplayString(delegate, allowSideEffects));
    }
}
