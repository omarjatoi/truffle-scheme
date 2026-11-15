package scheme.runtime.value;

import com.oracle.truffle.api.interop.TruffleObject;

/** A vector. Literal vectors are immutable. */
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
}
