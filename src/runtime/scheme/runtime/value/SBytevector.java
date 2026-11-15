package scheme.runtime.value;

import com.oracle.truffle.api.interop.TruffleObject;

/** A bytevector. Literal bytevectors are immutable. Elements are unsigned: use {@code bytes[i] & 0xff}. */
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
}
