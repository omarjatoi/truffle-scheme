package scheme.runtime.value;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;

import scheme.runtime.SchemeError;

/** A character: one Unicode scalar value. Characters 0-255 are cached. */
public final class SChar implements TruffleObject {

    private static final SChar[] CACHE = new SChar[256];

    static {
        for (int i = 0; i < CACHE.length; i++) {
            CACHE[i] = new SChar(i);
        }
    }

    public final int codePoint;

    private SChar(int codePoint) {
        this.codePoint = codePoint;
    }

    public static SChar of(int codePoint) {
        if (codePoint >= 0 && codePoint < CACHE.length) {
            return CACHE[codePoint];
        }
        if (codePoint < 0 || codePoint > Character.MAX_CODE_POINT || (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE)) {
            throw notScalarValue(codePoint);
        }
        return new SChar(codePoint);
    }

    @TruffleBoundary
    private static SchemeError notScalarValue(int codePoint) {
        return new SchemeError("not a Unicode scalar value: " + codePoint);
    }

    @Override
    @TruffleBoundary
    public String toString() {
        return new String(Character.toChars(codePoint));
    }
}
