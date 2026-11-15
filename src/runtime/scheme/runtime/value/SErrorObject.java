package scheme.runtime.value;

import com.oracle.truffle.api.interop.TruffleObject;

/**
 * An error object, as created by {@code error} or by a primitive that fails (design.md D8).
 * {@code kind} is what {@code file-error?} and {@code read-error?} test.
 */
public final class SErrorObject implements TruffleObject {

    public enum Kind {
        FILE,
        READ,
        OTHER
    }

    /** A Scheme string. */
    public final Object message;
    /** A Scheme list. */
    public final Object irritants;
    public final Kind kind;

    public SErrorObject(Object message, Object irritants, Kind kind) {
        this.message = message;
        this.irritants = irritants;
        this.kind = kind;
    }
}
