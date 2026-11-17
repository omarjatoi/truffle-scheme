package scheme.runtime.value;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import scheme.runtime.InteropValues;
import scheme.runtime.SchemeError;
import scheme.runtime.SchemeLanguage;

/**
 * A mutable Scheme string with O(1) indexing and {@code string-set!} (design.md D1). It is stored as
 * Latin-1 bytes until a character above 255 is stored, then as UTF-32 code points. Literals are
 * immutable.
 */
@ExportLibrary(InteropLibrary.class)
public final class SString implements TruffleObject {

    private byte[] latin1;
    private int[] wide;
    private final int length;
    private final boolean immutable;

    private SString(byte[] latin1, int[] wide, int length, boolean immutable) {
        this.latin1 = latin1;
        this.wide = wide;
        this.length = length;
        this.immutable = immutable;
    }

    /** A new mutable string. */
    public static SString of(String s) {
        return fromJava(s, false);
    }

    /** An immutable string, for literals. */
    public static SString literal(String s) {
        return fromJava(s, true);
    }

    /** A new mutable string of {@code length} copies of {@code codePoint}. */
    public static SString make(int length, int codePoint) {
        if (codePoint <= 0xff) {
            byte[] bytes = new byte[length];
            java.util.Arrays.fill(bytes, (byte) codePoint);
            return new SString(bytes, null, length, false);
        }
        int[] cps = new int[length];
        java.util.Arrays.fill(cps, codePoint);
        return new SString(null, cps, length, false);
    }

    @TruffleBoundary
    private static SString fromJava(String s, boolean immutable) {
        int[] cps = s.codePoints().toArray();
        boolean narrow = true;
        for (int cp : cps) {
            if (cp > 0xff) {
                narrow = false;
                break;
            }
        }
        if (!narrow) {
            return new SString(null, cps, cps.length, immutable);
        }
        byte[] bytes = new byte[cps.length];
        for (int i = 0; i < cps.length; i++) {
            bytes[i] = (byte) cps[i];
        }
        return new SString(bytes, null, cps.length, immutable);
    }

    public int length() {
        return length;
    }

    public boolean isLatin1() {
        return latin1 != null;
    }

    public boolean isImmutable() {
        return immutable;
    }

    public int codePointAt(int index) {
        if (index < 0 || index >= length) {
            throw outOfRange(index);
        }
        return latin1 != null ? latin1[index] & 0xff : wide[index];
    }

    public void set(int index, int codePoint) {
        if (immutable) {
            throw immutableString();
        }
        if (index < 0 || index >= length) {
            throw outOfRange(index);
        }
        if (latin1 != null) {
            if (codePoint <= 0xff) {
                latin1[index] = (byte) codePoint;
                return;
            }
            widen();
        }
        wide[index] = codePoint;
    }

    /**
     * Copies {@code from}'s characters from {@code start} to {@code end} into this string from
     * {@code at} (plan.md M8-04): {@code substring}, {@code string-append} and {@code string-copy!}.
     * The caller has checked the range against both strings. Between two strings of the same
     * storage it is one array copy, which, like {@code string-copy!}, is correct for overlapping
     * ranges of one string; otherwise it copies character by character, from the end if the ranges
     * overlap so that a later source character is read before it is overwritten.
     */
    @TruffleBoundary
    public void copyFrom(int at, SString from, int start, int end) {
        if (immutable && end > start) {
            throw immutableString();
        }
        if (start < 0 || end > from.length || start > end || at < 0 || at > length - (end - start)) {
            throw outOfRange(at);
        }
        if (latin1 != null && from.latin1 != null) {
            System.arraycopy(from.latin1, start, latin1, at, end - start);
        } else if (wide != null && from.wide != null) {
            System.arraycopy(from.wide, start, wide, at, end - start);
        } else if (from == this && at > start) {
            for (int i = end - 1; i >= start; i--) {
                set(at + i - start, from.codePointAt(i));
            }
        } else {
            for (int i = start; i < end; i++) {
                set(at + i - start, from.codePointAt(i));
            }
        }
    }

    @TruffleBoundary
    private void widen() {
        int[] cps = new int[length];
        for (int i = 0; i < length; i++) {
            cps[i] = latin1[i] & 0xff;
        }
        wide = cps;
        latin1 = null;
    }

    @TruffleBoundary
    private SchemeError outOfRange(int index) {
        return new SchemeError("string index out of range: " + index + " (length " + length + ")");
    }

    @TruffleBoundary
    private static SchemeError immutableString() {
        return new SchemeError("string is immutable");
    }

    @Override
    @TruffleBoundary
    public String toString() {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.appendCodePoint(codePointAt(i));
        }
        return sb.toString();
    }

    // A string to interop (design.md D10). Other languages get a copy (asString).

    @ExportMessage
    boolean isString() {
        return true;
    }

    @ExportMessage
    @TruffleBoundary
    String asString() {
        return toString();
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
        return SchemeType.STRING;
    }

    @ExportMessage
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return InteropValues.display(this);
    }
}
