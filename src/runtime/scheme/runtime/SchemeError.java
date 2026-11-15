package scheme.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.exception.AbstractTruffleException;

import scheme.runtime.value.SConstant;
import scheme.runtime.value.SErrorObject;
import scheme.runtime.value.SString;

/**
 * A Scheme-level error raised by Java code (design.md D8). It carries the {@link SErrorObject} that
 * the calling operation passes to Scheme {@code raise} in the same frame.
 */
public final class SchemeError extends AbstractTruffleException {

    private static final long serialVersionUID = 1L;

    private final transient SErrorObject errorObject;

    public SchemeError(SErrorObject errorObject) {
        super(messageOf(errorObject));
        this.errorObject = errorObject;
    }

    /** An error with no irritants, of kind {@code OTHER}. */
    @TruffleBoundary
    public SchemeError(String message) {
        this(new SErrorObject(SString.of(message), SConstant.EMPTY_LIST, SErrorObject.Kind.OTHER));
    }

    public SErrorObject getErrorObject() {
        return errorObject;
    }

    @TruffleBoundary
    private static String messageOf(SErrorObject errorObject) {
        return String.valueOf(errorObject.message);
    }
}
