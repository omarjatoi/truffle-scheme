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

    /**
     * A new mutable string of the UTF-16 text in {@code chars} from {@code offset}, {@code length}
     * units long: what a reader read, without a {@code String} in between. A surrogate pair in the
     * range is one character, as in {@link #of(String)}; a half of one at either end of the range
     * is a lone surrogate.
     */
    @TruffleBoundary
    public static SString of(char[] chars, int offset, int length) {
        java.util.Objects.checkFromIndexSize(offset, length, chars.length);
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            char c = chars[offset + i];
            if (c > 0xff) {
                return wide(bytes, i, chars, offset, length, false);
            }
            bytes[i] = (byte) c;
        }
        return new SString(bytes, null, length, false);
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

    /**
     * One pass over the text: Latin-1 bytes while every unit is at most 255, which is one character
     * each (a surrogate is above 255); at the first unit above, the code points so far and the rest.
     */
    @TruffleBoundary
    private static SString fromJava(String s, boolean immutable) {
        int length = s.length();
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            char c = s.charAt(i);
            if (c > 0xff) {
                // A String with a unit above 255 holds UTF-16, so its array is a copy of that.
                return wide(bytes, i, s.toCharArray(), 0, length, immutable);
            }
            bytes[i] = (byte) c;
        }
        return new SString(bytes, null, length, immutable);
    }

    /**
     * The UTF-32 string of the {@code units} UTF-16 units in {@code chars} from {@code offset}, whose
     * first {@code start} units are Latin-1 and in {@code latin1} already: pairs a high surrogate
     * with the low one after it, as {@code String.codePoints()} does, and leaves a lone one as it
     * is. The text has at most {@code units} code points, so the array is trimmed only if a pair
     * made it shorter.
     */
    private static SString wide(byte[] latin1, int start, char[] chars, int offset, int units, boolean immutable) {
        int[] cps = new int[units];
        for (int i = 0; i < start; i++) {
            cps[i] = latin1[i] & 0xff;
        }
        int n = start;
        for (int i = start; i < units; i++) {
            char c = chars[offset + i];
            if (Character.isHighSurrogate(c) && i + 1 < units && Character.isLowSurrogate(chars[offset + i + 1])) {
                cps[n++] = Character.toCodePoint(c, chars[offset + ++i]);
            } else {
                cps[n++] = c;
            }
        }
        return new SString(null, n == units ? cps : java.util.Arrays.copyOf(cps, n), n, immutable);
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

    /**
     * Compares {@code a} and {@code b} by code point, lexicographically: negative, zero or positive
     * as {@code a} comes before, is equal to or comes after {@code b} ({@code string<?} and the
     * other comparisons). Each pair of storages has a loop of its own, chosen before it (design.md
     * "Representations chosen outside loops", plan.md M10-06): a loop of {@link #codePointAt}, which
     * tests the storage at every character, let Graal's speculative guard movement hoist the null
     * check of {@code wide} out of the loop, where it ran for Latin-1 strings too, so compiled code
     * that inlined a comparison deoptimized at its first one. Truffle remembers that speculation
     * per call target, and a split is one, so each split paid it again.
     */
    public static int compare(SString a, SString b) {
        int n = Math.min(a.length, b.length);
        byte[] al = a.latin1;
        byte[] bl = b.latin1;
        int c;
        if (al != null) {
            c = bl != null ? compare(al, bl, n) : compare(al, b.wide, n);
        } else {
            c = bl != null ? -compare(bl, a.wide, n) : compare(a.wide, b.wide, n);
        }
        return c != 0 ? c : Integer.compare(a.length, b.length);
    }

    private static int compare(byte[] x, byte[] y, int n) {
        for (int i = 0; i < n; i++) {
            int cx = x[i] & 0xff;
            int cy = y[i] & 0xff;
            if (cx != cy) {
                return cx < cy ? -1 : 1;
            }
        }
        return 0;
    }

    private static int compare(byte[] x, int[] y, int n) {
        for (int i = 0; i < n; i++) {
            int cx = x[i] & 0xff;
            int cy = y[i];
            if (cx != cy) {
                return cx < cy ? -1 : 1;
            }
        }
        return 0;
    }

    private static int compare(int[] x, int[] y, int n) {
        for (int i = 0; i < n; i++) {
            int cx = x[i];
            int cy = y[i];
            if (cx != cy) {
                return cx < cy ? -1 : 1;
            }
        }
        return 0;
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
