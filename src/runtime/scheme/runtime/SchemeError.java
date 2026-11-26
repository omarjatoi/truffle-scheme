package scheme.runtime;

import java.util.List;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleStackTrace;
import com.oracle.truffle.api.TruffleStackTraceElement;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.ContinuationRootNode;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.FrameInstance.FrameAccess;
import com.oracle.truffle.api.interop.ExceptionType;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;

import scheme.runtime.printer.Printer;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SErrorObject;
import scheme.runtime.value.SPair;
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

    private SchemeError(SErrorObject raised, SourceSection location, boolean incompleteSource, SErrorObject errorObject) {
        super(describe(raised));
        this.errorObject = errorObject;
        this.uncaught = raised;
        this.raised = raised;
        this.syntax = true;
        this.incompleteSource = incompleteSource;
        this.syntaxLocation = location;
    }

    /**
     * An error in program text, from the reader or the expander: a parse error to embedders, and
     * {@code incompleteSource} if the text ended inside a datum (design.md D13). Its message is
     * {@code "syntax error: <what is wrong>"}, and {@code form}, unless null, its irritant (design.md
     * D8, "Messages"); the location is not in the message.
     */
    @TruffleBoundary
    public static SchemeError syntax(String message, Object form, SourceSection location, boolean incompleteSource) {
        Object irritants = form == null ? SConstant.EMPTY_LIST : new SPair(form, SConstant.EMPTY_LIST);
        return new SchemeError(new SErrorObject(SString.of(message), irritants, SErrorObject.Kind.OTHER), location, incompleteSource, null);
    }

    /**
     * A syntax error from {@code syntax-error} (R7RS §4.3.3): like {@link #syntax}, and it carries
     * the error object that {@code eval} and {@code load} raise instead (see {@link #getErrorObject}).
     * Its message is {@code "syntax error: "} and the error object's.
     */
    @TruffleBoundary
    public static SchemeError syntax(SourceSection location, SErrorObject errorObject) {
        SErrorObject shown = new SErrorObject(SString.of("syntax error: " + Printer.print(errorObject.message, Printer.Mode.DISPLAY)), errorObject.irritants, SErrorObject.Kind.OTHER);
        return new SchemeError(shown, location, false, errorObject);
    }

    /**
     * What {@code eval} or {@code load} raises for this syntax error (R7RS §6.12): an error object has
     * no location, so its message starts with the form's, {@code file:line:column: } (design.md D8,
     * "Messages"), when the form has one; one from {@code syntax-error} is raised as it was given.
     */
    @TruffleBoundary
    public SchemeError raisable() {
        if (!syntax) {
            return this;
        }
        if (errorObject != null) {
            return new SchemeError(errorObject);
        }
        SErrorObject shown = (SErrorObject) raised;
        String at = syntaxLocation == null || !syntaxLocation.isAvailable() ? "" : where(syntaxLocation) + ": ";
        return new SchemeError(new SErrorObject(SString.of(at + Printer.print(shown.message, Printer.Mode.DISPLAY)), shown.irritants, SErrorObject.Kind.OTHER));
    }

    /** A location as Truffle's stack traces show it, {@code file:line:column} (design.md D8, D13). */
    @TruffleBoundary
    public static String where(SourceSection section) {
        return section.getSource().getName() + ":" + section.getStartLine() + ":" + section.getStartColumn();
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
        return syntax ? syntaxLocation : innermostLocation(this);
    }

    private static SourceSection innermostLocation(Throwable exception) {
        for (TruffleStackTraceElement element : TruffleStackTrace.getStackTrace(exception)) {
            SourceSection section = locationOf(element);
            if (section != null) {
                return section;
            }
        }
        return null;
    }

    /** The element's location, if its code isn't internal. */
    private static SourceSection locationOf(TruffleStackTraceElement element) {
        if (isInternal(element.getTarget().getRootNode())) {
            return null;
        }
        BytecodeNode bytecode = BytecodeNode.get(element);
        return bytecode == null ? null : bytecode.getSourceLocation(element.getBytecodeIndex());
    }

    /** Whether the code is internal: a resumed frame's continuation root's procedure says. */
    private static boolean isInternal(RootNode root) {
        if (root instanceof ContinuationRootNode continuation) {
            return ((RootNode) continuation.getSourceRootNode()).isInternal();
        }
        return root.isInternal();
    }

    /**
     * Where an error raised at {@code node} now would be located ({@link #location}): the innermost
     * frame with a location whose code isn't internal, counting the frames a prompt holds on the
     * heap (its asynchronous frames: a spill's, and every frame under torture) where its driver is.
     * For an exit, which is Truffle's exception rather than an error, and which Truffle locates at
     * the node it is given (design.md D8). Never on a fast path: it walks the stack.
     */
    @TruffleBoundary
    public static SourceSection locate(Node node) {
        // The current frame's location is the node's: Truffle finds it, in a resumed frame too, as
        // for an exception thrown there. A frame instance has only its call node.
        Probe probe = new Probe(node);
        List<TruffleStackTraceElement> trace = TruffleStackTrace.fillIn(probe) == null ? null : TruffleStackTrace.getStackTrace(probe);
        TruffleStackTraceElement current = trace == null || trace.isEmpty() ? null : trace.get(0);
        boolean[] first = {true};
        return Truffle.getRuntime().iterateFrames(frame -> {
            SourceSection section = pendingLocation(frame);
            if (section != null) {
                return section;
            }
            if (first[0]) {
                first[0] = false;
                return current == null ? null : locationOf(current);
            }
            if (!(frame.getCallTarget() instanceof RootCallTarget target) || isInternal(target.getRootNode())) {
                return null;
            }
            BytecodeNode bytecode = BytecodeNode.get(frame);
            return bytecode == null ? null : bytecode.getSourceLocation(frame);
        });
    }

    /** An exception made only for the stack trace of where it is made, and never thrown. */
    private static final class Probe extends AbstractTruffleException {
        private static final long serialVersionUID = 1L;

        Probe(Node location) {
            super(location);
        }
    }

    /** The location of the innermost of the frames a prompt's driver holds on the heap, if any. */
    private static SourceSection pendingLocation(FrameInstance frame) {
        List<TruffleStackTraceElement> pending = TruffleStackTrace.getAsynchronousStackTrace(frame.getCallTarget(), frame.getFrame(FrameAccess.READ_ONLY));
        if (pending != null) {
            for (TruffleStackTraceElement element : pending) {
                SourceSection section = locationOf(element);
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
        return describe(errorObject);
    }

    // The form of the implementation's errors (design.md D8, "Messages"): an error object whose
    // message is "<who>: <what is wrong>" and whose irritants are the data it is about.

    /**
     * An error object's printed text: its message, then {@code ": "} and each irritant written,
     * separated by spaces; a message that ends with {@code ':'} gets only the space (design.md D8,
     * "Messages").
     */
    @TruffleBoundary
    public static String describe(SErrorObject errorObject) {
        String message = Printer.print(errorObject.message, Printer.Mode.DISPLAY);
        if (!(errorObject.irritants instanceof SPair)) {
            return message;
        }
        StringBuilder text = new StringBuilder(message).append(message.endsWith(":") ? " " : ": ");
        for (Object l = errorObject.irritants; l instanceof SPair p; l = p.cdr) {
            text.append(Printer.print(p.car, Printer.Mode.WRITE));
            if (p.cdr instanceof SPair) {
                text.append(' ');
            }
        }
        return text.toString();
    }

    /** An error of kind {@code OTHER} with this message and irritants. */
    @TruffleBoundary
    public static SchemeError of(String message, Object... irritants) {
        return new SchemeError(new SErrorObject(SString.of(message), list(irritants), SErrorObject.Kind.OTHER));
    }

    /** An error of this kind with this message and irritants. */
    @TruffleBoundary
    public static SchemeError of(SErrorObject.Kind kind, String message, Object... irritants) {
        return new SchemeError(new SErrorObject(SString.of(message), list(irritants), kind));
    }

    private static Object list(Object[] items) {
        Object list = SConstant.EMPTY_LIST;
        for (int i = items.length - 1; i >= 0; i--) {
            list = new SPair(items[i], list);
        }
        return list;
    }

    /**
     * An argument that isn't what {@code who} takes (design.md D8, "Messages"):
     * {@code <who>: argument <position> is not <what>}, the argument the irritant. {@code position}
     * counts the call's arguments from 1; {@code what} is a noun phrase without its article
     * ({@code "pair"}, {@code "exact integer"}) or with one ({@code "an index from 0 to 2"}).
     */
    @TruffleBoundary
    public static SchemeError argument(String who, int position, String what, Object value) {
        return of(who + ": argument " + position + " is not " + article(what), value);
    }

    /** An argument with an element that isn't what {@code who} takes: the element the irritant. */
    @TruffleBoundary
    public static SchemeError element(String who, int position, String what, Object element) {
        return of(who + ": argument " + position + " has an element that is not " + article(what), element);
    }

    /**
     * A call of {@code who} with {@code count} arguments, where it takes {@code min} to {@code max}
     * ({@code max} -1: any number): {@code <who>: expected <n> arguments, got <count>}.
     */
    @TruffleBoundary
    public static SchemeError arity(String who, int min, int max, int count) {
        String expected = max < 0 ? "at least " + min : min == max ? Integer.toString(min) : min + " to " + max;
        return of(who + ": expected " + expected + (max == 1 && min == 1 || max < 0 && min == 1 ? " argument" : " arguments") + ", got " + count);
    }

    /** What an index from {@code min} to {@code max} is, for {@link #argument}. */
    @TruffleBoundary
    public static String index(long min, long max) {
        return max < min ? "an index (the range is empty)" : "an index from " + min + " to " + max;
    }

    /** {@code what} with its article: as given if it has one, else {@code a} or {@code an}. */
    private static String article(String what) {
        if (what.startsWith("a ") || what.startsWith("an ") || what.startsWith("the ")) {
            return what;
        }
        return ("aeio".indexOf(what.charAt(0)) >= 0 ? "an " : "a ") + what;
    }
}
