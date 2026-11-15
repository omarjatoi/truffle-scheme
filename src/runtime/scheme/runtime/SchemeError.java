package scheme.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.exception.AbstractTruffleException;

import scheme.runtime.value.SConstant;
import scheme.runtime.value.SErrorObject;
import scheme.runtime.value.SString;

/**
 * A Scheme-level error raised by Java code (design.md D8). It carries the {@link SErrorObject} that
 * the calling operation passes to Scheme {@code raise} in the same frame.
 *
 * <p>An <em>uncaught</em> error is different: {@code raise} found no handler, so it ends the
 * program. It carries the raised object, and call sites let it through.
 */
public final class SchemeError extends AbstractTruffleException {

    private static final long serialVersionUID = 1L;

    private final transient SErrorObject errorObject;
    /** For an uncaught raise: the raised object; otherwise null. */
    private final transient Object uncaught;

    public SchemeError(SErrorObject errorObject) {
        super(messageOf(errorObject));
        this.errorObject = errorObject;
        this.uncaught = null;
    }

    private SchemeError(String message, Object raised) {
        super(message);
        this.errorObject = raised instanceof SErrorObject e ? e : null;
        this.uncaught = raised;
    }

    /** {@code raise} found no handler for {@code raised}: ends the program with this message. */
    @TruffleBoundary
    public static SchemeError uncaught(String message, Object raised) {
        return new SchemeError(message, raised);
    }

    /** Whether a call site passes this error to Scheme {@code raise} (it isn't already uncaught). */
    public boolean isRaisable() {
        return uncaught == null;
    }

    /** The raised object of an uncaught raise, or null. */
    public Object getUncaught() {
        return uncaught;
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
