package scheme.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleStackTrace;
import com.oracle.truffle.api.TruffleStackTraceElement;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.ContinuationRootNode;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.interop.ExceptionType;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;

import scheme.runtime.value.SConstant;
import scheme.runtime.value.SErrorObject;
import scheme.runtime.value.SString;
import scheme.runtime.value.SchemeType;

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
 *
 * <p>To embedders, the exception <em>is</em> the object it raises (design.md D10, M7-06): every
 * other interop message goes to {@link #raised}, so {@code PolyglotException.getGuestObject()} is
 * that object (an error object, with its message and irritants as members, or whatever
 * {@code raise} was given) as well as an exception, and passing it back to Scheme passes that
 * object ({@link InteropValues#toScheme}).
 */
@ExportLibrary(value = InteropLibrary.class, delegateTo = "raised")
public final class SchemeError extends AbstractTruffleException {

    private static final long serialVersionUID = 1L;

    private final transient SErrorObject errorObject;
    /** For an uncaught raise: the raised object; otherwise null. */
    private final transient Object uncaught;
    /**
     * What embedders see this exception as: the raised object of an uncaught raise, otherwise the
     * error object (one made from the message for a syntax error that has none).
     */
    final transient Object raised;
    private final boolean syntax;
    private final boolean incompleteSource;
    private final transient SourceSection syntaxLocation;

    public SchemeError(SErrorObject errorObject) {
        super(messageOf(errorObject));
        this.errorObject = errorObject;
        this.uncaught = null;
        this.raised = errorObject;
        this.syntax = false;
        this.incompleteSource = false;
        this.syntaxLocation = null;
    }

    private SchemeError(String message, Object raised) {
        this(message, raised, false);
    }

    private SchemeError(String message, Object raised, boolean raisable) {
        super(message);
        this.errorObject = raised instanceof SErrorObject e ? e : null;
        this.uncaught = raisable ? null : raised;
        this.raised = raised;
        this.syntax = false;
        this.incompleteSource = false;
        this.syntaxLocation = null;
    }

    private SchemeError(String message, SourceSection location, boolean incompleteSource, SErrorObject errorObject) {
        super(message);
        this.errorObject = errorObject;
        this.uncaught = message;
        this.raised = errorObject != null ? errorObject : new SErrorObject(SString.of(message), SConstant.EMPTY_LIST, SErrorObject.Kind.OTHER);
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

    // The exception messages: with every other message delegated to the raised object, this class
    // answers them itself (AbstractTruffleException's defaults would go to the raised object too).

    @ExportMessage
    boolean isException() {
        return true;
    }

    @ExportMessage
    RuntimeException throwException() {
        throw this;
    }

    @ExportMessage
    ExceptionType getExceptionType() {
        return syntax ? ExceptionType.PARSE_ERROR : ExceptionType.RUNTIME_ERROR;
    }

    @ExportMessage
    int getExceptionExitStatus() throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    @ExportMessage
    boolean hasExceptionMessage() {
        return true;
    }

    @ExportMessage
    @TruffleBoundary
    Object getExceptionMessage() {
        return String.valueOf(getMessage());
    }

    @ExportMessage
    boolean hasExceptionCause() {
        return false;
    }

    @ExportMessage
    Object getExceptionCause() throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    /**
     * The guest stack trace as Truffle makes it for any {@code AbstractTruffleException}
     * ({@code createGuestStackTrace}): an array of the frames the error left, each with its
     * executable name and source location (design.md D8). They are the frames
     * {@code PolyglotException.getPolyglotStackTrace()} shows, a spill's and a resumed
     * continuation's included (design.md "Spilled frames in tools"). Made when asked for.
     */
    @ExportMessage
    boolean hasExceptionStackTrace() {
        return true;
    }

    @ExportMessage
    @TruffleBoundary
    Object getExceptionStackTrace() {
        return createGuestStackTrace(this);
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

    /**
     * A syntax error's form, or the location of the innermost Scheme frame with one whose code isn't
     * internal: the frame the guest stack trace starts with, which leaves out the prelude's (a
     * {@code map} or a {@code raise} the error left), design.md D8. A frame resumed from a
     * continuation runs in the Bytecode DSL's continuation root, which is never internal
     * (docs/upstream.md): its procedure's root says.
     */
    private SourceSection location() {
        if (syntax) {
            return syntaxLocation;
        }
        for (TruffleStackTraceElement element : TruffleStackTrace.getStackTrace(this)) {
            RootNode root = element.getTarget().getRootNode();
            if (root instanceof ContinuationRootNode continuation) {
                root = (RootNode) continuation.getSourceRootNode();
            }
            if (root.isInternal()) {
                continue;
            }
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

    // The raised object's language, meta object and display string. A raised long, double or
    // boolean has no language of its own, so these come from Scheme's view of it (LanguageView):
    // an uncaught (raise #t) shows as #t, with the meta object boolean (design.md D8).

    private boolean raisedIsPrimitive() {
        return raised instanceof Long || raised instanceof Double || raised instanceof Boolean;
    }

    @ExportMessage
    boolean hasLanguageId(@CachedLibrary("this.raised") InteropLibrary interop) {
        return raisedIsPrimitive() || interop.hasLanguageId(raised);
    }

    @ExportMessage
    String getLanguageId(@CachedLibrary("this.raised") InteropLibrary interop) throws UnsupportedMessageException {
        return raisedIsPrimitive() ? SchemeLanguage.ID : interop.getLanguageId(raised);
    }

    @ExportMessage
    boolean hasMetaObject(@CachedLibrary("this.raised") InteropLibrary interop) {
        return raisedIsPrimitive() || interop.hasMetaObject(raised);
    }

    @ExportMessage
    Object getMetaObject(@CachedLibrary("this.raised") InteropLibrary interop) throws UnsupportedMessageException {
        return raisedIsPrimitive() ? SchemeType.of(raised) : interop.getMetaObject(raised);
    }

    @ExportMessage
    @TruffleBoundary
    Object toDisplayString(boolean allowSideEffects, @CachedLibrary("this.raised") InteropLibrary interop) {
        return raisedIsPrimitive() ? InteropValues.display(raised) : interop.toDisplayString(raised, allowSideEffects);
    }

    /** {@code raise} found no handler for {@code raised}: ends the program with this message. */
    @TruffleBoundary
    public static SchemeError uncaught(String message, Object raised) {
        return new SchemeError(message, raised);
    }

    /**
     * An error that a call site raises as {@code raised} itself, whatever it is (design.md D20): a
     * foreign exception, or what a callback raised and left uncaught, raised again at the foreign
     * call's site.
     */
    @TruffleBoundary
    public static SchemeError raising(Object raised, String message) {
        return new SchemeError(message, raised, true);
    }

    /**
     * What a call site passes to Scheme {@code raise} for a raisable error: the error object, or what
     * {@link #raising} was given.
     */
    public Object getRaised() {
        return raised;
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
