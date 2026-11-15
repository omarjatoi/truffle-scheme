package r7rs.runtime;

import com.oracle.truffle.api.exception.AbstractTruffleException;

/**
 * A Scheme-level error raised by Java code (design.md D8). M0-01 carries only a message; the
 * {@code SErrorObject} payload (message, irritants, kind) arrives with the value types in M1-03.
 */
public final class SchemeError extends AbstractTruffleException {

    private static final long serialVersionUID = 1L;

    public SchemeError(String message) {
        super(message);
    }
}
