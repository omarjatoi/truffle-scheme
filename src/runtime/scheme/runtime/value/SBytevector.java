package scheme.runtime.value;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import scheme.runtime.InteropValues;
import scheme.runtime.SchemeLanguage;

/** A bytevector. Literal bytevectors are immutable. Elements are unsigned: use {@code bytes[i] & 0xff}. */
@ExportLibrary(InteropLibrary.class)
public final class SBytevector implements TruffleObject {

    public final byte[] bytes;
    public final boolean immutable;

    public SBytevector(byte[] bytes) {
        this(bytes, false);
    }

    public SBytevector(byte[] bytes, boolean immutable) {
        this.bytes = bytes;
        this.immutable = immutable;
    }

    // An array of exact integers 0-255 to interop (design.md D10). Literal bytevectors are read-only.

    @ExportMessage
    boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
    long getArraySize() {
        return bytes.length;
    }

    @ExportMessage
    boolean isArrayElementReadable(long index) {
        return index >= 0 && index < bytes.length;
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
        return (long) (bytes[(int) index] & 0xff);
    }

    @ExportMessage
    void writeArrayElement(long index, Object value) throws InvalidArrayIndexException, UnsupportedMessageException, UnsupportedTypeException {
        if (!isArrayElementReadable(index)) {
            throw InvalidArrayIndexException.create(index);
        }
        if (immutable) {
            throw UnsupportedMessageException.create();
        }
        if (!(InteropValues.toScheme(value) instanceof Long b) || b < 0 || b > 255) {
            throw UnsupportedTypeException.create(new Object[]{value}, "a byte is an exact integer from 0 to 255");
        }
        bytes[(int) index] = (byte) (long) b;
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
        return SchemeType.BYTEVECTOR;
    }

    @ExportMessage
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return InteropValues.display(this);
    }
}
