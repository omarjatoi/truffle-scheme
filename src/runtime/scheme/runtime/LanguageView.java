package scheme.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import scheme.runtime.value.SchemeType;

/**
 * Scheme's view of a value that has no language of its own, or another language's
 * ({@link SchemeLanguage#getLanguageView}, design.md D10): {@code long}, {@code double} and
 * {@code boolean} show as Scheme writes them ({@code #t}, {@code +inf.0}) with their Scheme type as
 * meta object, and a foreign value as Scheme writes it ({@code #<foreign ...>}) with its own meta
 * object. Every other message goes to the value itself.
 */
@ExportLibrary(value = InteropLibrary.class, delegateTo = "delegate")
final class LanguageView implements TruffleObject {

    final Object delegate;

    LanguageView(Object delegate) {
        this.delegate = delegate;
    }

    private boolean isPrimitive() {
        return delegate instanceof Long || delegate instanceof Double || delegate instanceof Boolean;
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
    boolean hasMetaObject(@CachedLibrary("this.delegate") InteropLibrary interop) {
        return isPrimitive() || interop.hasMetaObject(delegate);
    }

    @ExportMessage
    Object getMetaObject(@CachedLibrary("this.delegate") InteropLibrary interop) throws UnsupportedMessageException {
        if (isPrimitive()) {
            return SchemeType.of(delegate);
        }
        return interop.getMetaObject(delegate);
    }

    @ExportMessage
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        if (isPrimitive()) {
            return InteropValues.display(delegate);
        }
        return InteropValues.displayForeign(delegate);
    }
}
