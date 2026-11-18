package scheme.runtime.staticobject;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import scheme.runtime.InteropValues;
import scheme.runtime.SchemeLanguage;
import scheme.runtime.value.SRecordType;
import scheme.runtime.value.SString;
import scheme.runtime.value.SVector;

/**
 * An instance of an {@link SRecordType}: a static object of Truffle's Static Object Model (design.md
 * D1, plan.md M7-04). Its fields are its type's {@link SRecordType#properties}, stored in the object
 * itself (Java fields of a class the model generates, a subclass of this one; arrays in a native
 * image), so a field access compiles to a field load. Only the type's {@link SRecordType#factory}
 * makes one.
 *
 * <p>
 * Alone in its package, the one the language's module exports to everyone: the model defines the
 * classes it generates in class loaders of its own, so in unnamed modules, which no qualified
 * export can name (design.md D11, "Modules").
 */
@ExportLibrary(InteropLibrary.class)
public class SRecord implements TruffleObject {

    public final SRecordType type;

    /** For the Static Object Model's generated subclasses: use {@link SRecordType#factory}. */
    public SRecord(SRecordType type) {
        this.type = type;
    }

    /** What the Static Object Model implements to make records of a shape. */
    public interface Factory {
        SRecord create(SRecordType type);
    }

    /** Field {@code i}'s value (for the runtime and tools; record procedures use the properties). */
    public final Object get(int i) {
        return type.properties[i].getObject(this);
    }

    /** Sets field {@code i}. */
    public final void set(int i, Object value) {
        type.properties[i].setObject(this, value);
    }

    // An object to interop (design.md D10): its fields are read-only members, and its meta object
    // is its record type.

    @ExportMessage
    final boolean hasMembers() {
        return true;
    }

    @ExportMessage
    @TruffleBoundary
    final Object getMembers(@SuppressWarnings("unused") boolean includeInternal) {
        Object[] names = new Object[type.fieldNames.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = SString.of(type.fieldNames[i]);
        }
        return new SVector(names, true);
    }

    @ExportMessage
    @TruffleBoundary
    final boolean isMemberReadable(String member) {
        return type.fieldIndex(member) >= 0;
    }

    @ExportMessage
    @TruffleBoundary
    final Object readMember(String member) throws UnknownIdentifierException {
        int i = type.fieldIndex(member);
        if (i < 0) {
            throw UnknownIdentifierException.create(member);
        }
        return get(i);
    }

    // Interop (design.md D10).

    @ExportMessage
    final boolean hasLanguageId() {
        return true;
    }

    @ExportMessage
    final String getLanguageId() {
        return SchemeLanguage.ID;
    }

    @ExportMessage
    final boolean hasMetaObject() {
        return true;
    }

    @ExportMessage
    final Object getMetaObject() {
        return type;
    }

    @ExportMessage
    final Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return InteropValues.display(this);
    }
}
