package scheme.runtime.value;

import java.math.BigInteger;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;

/**
 * An exact integer outside the {@code long} range. Results that fit in a {@code long} are always
 * demoted, so each integer has exactly one representation (design.md D1).
 */
public final class SBigInteger implements TruffleObject {

    public final BigInteger value;

    private SBigInteger(BigInteger value) {
        this.value = value;
    }

    /** The canonical exact integer for {@code value}: a {@code Long} if it fits, else an SBigInteger. */
    @TruffleBoundary
    public static Object normalize(BigInteger value) {
        return value.bitLength() < Long.SIZE ? (Object) value.longValue() : new SBigInteger(value);
    }

    @Override
    @TruffleBoundary
    public String toString() {
        return value.toString();
    }
}
