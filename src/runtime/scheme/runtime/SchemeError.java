package scheme.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleStackTrace;
import com.oracle.truffle.api.TruffleStackTraceElement;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.interop.ExceptionType;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.source.SourceSection;

import scheme.runtime.value.SConstant;
import scheme.runtime.value.SErrorObject;
import scheme.runtime.value.SString;

/**
 * A Scheme-level error raised by Java code (design.md D8). It carries the {@link SErrorObject} that
 * the calling operation passes to Scheme {@code raise} in the same frame.
 *
 * <p>An <em>uncaught</em> error is different: {@code raise} found no handler, so it ends the
 * program. It carries the raised object, and call sites let it through.
 *
 * <p>A <em>syntax</em> error comes from reading or expanding program text: never raised in Scheme,
 * it is a parse error to embedders and tools (design.md D13), possibly an incomplete one (the text
 * ended inside a datum, so a REPL can ask for more). Through interop every SchemeError reports its
 * exception type and source location: a syntax error its form's, a runtime error the innermost
 * Scheme frame's.
 */
@ExportLibrary(InteropLibrary.class)
public final class SchemeError extends AbstractTruffleException {

    private static final long serialVersionUID = 1L;

    private final transient SErrorObject errorObject;
    /** For an uncaught raise: the raised object; otherwise null. */
    private final transient Object uncaught;
    private final boolean syntax;
    private final boolean incompleteSource;
    private final transient SourceSection syntaxLocation;

    public SchemeError(SErrorObject errorObject) {
        super(messageOf(errorObject));
        this.errorObject = errorObject;
        this.uncaught = null;
        this.syntax = false;
        this.incompleteSource = false;
        this.syntaxLocation = null;
    }

    private SchemeError(String message, Object raised) {
        super(message);
        this.errorObject = raised instanceof SErrorObject e ? e : null;
        this.uncaught = raised;
        this.syntax = false;
        this.incompleteSource = false;
        this.syntaxLocation = null;
    }

    private SchemeError(String message, SourceSection location, boolean incompleteSource, SErrorObject errorObject) {
        super(message);
        this.errorObject = errorObject;
        this.uncaught = message;
        this.syntax = true;
        this.incompleteSource = incompleteSource;
        this.syntaxLocation = location;
    }

    /**
     * An error in program text, from the reader or the expander: a parse error to embedders, and
     * {@code incompleteSource} if the text ended inside a datum (design.md D13).
     */
    @TruffleBoundary
    public static SchemeError syntax(String message, SourceSection location, boolean incompleteSource) {
        return new SchemeError(message, location, incompleteSource, null);
    }

    /**
     * A syntax error from {@code syntax-error} (R7RS §4.3.3): like {@link #syntax}, and it carries
     * the error object that {@code eval} and {@code load} raise instead (see {@link #getErrorObject}).
     */
    @TruffleBoundary
    public static SchemeError syntax(String message, SourceSection location, SErrorObject errorObject) {
        return new SchemeError(message, location, false, errorObject);
    }

    public boolean isSyntaxError() {
        return syntax;
    }

    @ExportMessage
    ExceptionType getExceptionType() {
        return syntax ? ExceptionType.PARSE_ERROR : ExceptionType.RUNTIME_ERROR;
    }

    @ExportMessage
    boolean isExceptionIncompleteSource() {
        return incompleteSource;
    }

    @ExportMessage
    @TruffleBoundary
    boolean hasSourceLocation() {
        return location() != null;
    }

    @ExportMessage(name = "getSourceLocation")
    @TruffleBoundary
    SourceSection getSourceLocationMessage() throws UnsupportedMessageException {
        SourceSection location = location();
        if (location == null) {
            throw UnsupportedMessageException.create();
        }
        return location;
    }

    /** A syntax error's form, or the location of the innermost Scheme frame with one. */
    private SourceSection location() {
        if (syntax) {
            return syntaxLocation;
        }
        for (TruffleStackTraceElement element : TruffleStackTrace.getStackTrace(this)) {
            BytecodeNode bytecode = BytecodeNode.get(element);
            if (bytecode != null) {
                SourceSection section = bytecode.getSourceLocation(element.getBytecodeIndex());
                if (section != null) {
                    return section;
                }
            }
        }
        return null;
    }

    /** {@code raise} found no handler for {@code raised}: ends the program with this message. */
    @TruffleBoundary
    public static SchemeError uncaught(String message, Object raised) {
        return new SchemeError(message, raised);
    }

    /** Whether a call site passes this error to Scheme {@code raise} (not uncaught, not syntax). */
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

    /**
     * What a call site raises; for a syntax error, what {@code eval} or {@code load} raises instead
     * (null unless it came from {@code syntax-error}).
     */
    public SErrorObject getErrorObject() {
        return errorObject;
    }

    @TruffleBoundary
    private static String messageOf(SErrorObject errorObject) {
        return String.valueOf(errorObject.message);
    }
}
