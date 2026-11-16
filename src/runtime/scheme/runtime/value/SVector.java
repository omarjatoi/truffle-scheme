package scheme.runtime.value;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import scheme.runtime.InteropValues;
import scheme.runtime.SchemeLanguage;

/** A vector. Literal vectors are immutable. */
@ExportLibrary(InteropLibrary.class)
public final class SVector implements TruffleObject {

    public final Object[] elements;
    public final boolean immutable;

    public SVector(Object[] elements) {
        this(elements, false);
    }

    public SVector(Object[] elements, boolean immutable) {
        this.elements = elements;
        this.immutable = immutable;
    }

    // EXPLORATION (design.md D10): a vector is an array to interop; literal vectors are read-only.

    @ExportMessage
    boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
    long getArraySize() {
        return elements.length;
    }

    @ExportMessage
    boolean isArrayElementReadable(long index) {
        return index >= 0 && index < elements.length;
    }

    @ExportMessage
    boolean isArrayElementModifiable(long index) {
        return !immutable && isArrayElementReadable(index);
    }

    @ExportMessage
    boolean isArrayElementInsertable(@SuppressWarnings("unused") long index) {
        return false;
    }

    @ExportMessage
    Object readArrayElement(long index) throws InvalidArrayIndexException {
        if (!isArrayElementReadable(index)) {
            throw InvalidArrayIndexException.create(index);
        }
        return elements[(int) index];
    }

    @ExportMessage
    void writeArrayElement(long index, Object value) throws InvalidArrayIndexException, UnsupportedMessageException {
        if (!isArrayElementReadable(index)) {
            throw InvalidArrayIndexException.create(index);
        }
        if (immutable) {
            throw UnsupportedMessageException.create();
        }
        elements[(int) index] = InteropValues.toScheme(value);
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
