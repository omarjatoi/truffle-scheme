package scheme.runtime;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.graalvm.nativeimage.ImageInfo;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.ExceptionType;
import com.oracle.truffle.api.interop.HeapIsolationException;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnknownKeyException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.nodes.LanguageInfo;
import com.oracle.truffle.api.source.Source;

import scheme.runtime.value.SConstant;
import scheme.runtime.value.SErrorObject;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SString;
import scheme.runtime.value.SSymbol;

/**
 * Scheme's side of foreign calls (design.md D20): the count of foreign calls on the stack that
 * continuations are checked against, how foreign exceptions and interop errors become Scheme
 * errors, and the operations of {@code (graalvm polyglot)}. Calls of foreign executables, and
 * values coming in, are {@link ForeignNodes}'.
 *
 * <p>Every operation that enters another language or Java (a foreign call, a member read, an
 * {@code eval}) runs between {@link #enter} and {@link #exit}, which count it in
 * {@link SchemeContext#foreignDepth}: a callback that runs inside it sees the count, so a
 * continuation captured on one side of the foreign frames is never invoked on the other
 * ({@link Continuation#checkInvocable}). An exception the foreign code throws is caught as an
 * {@link AbstractTruffleException} and raised in Scheme at the call site ({@link #raised}); an
 * {@link InteropException} is a Scheme error ({@link #interopError}), as InteropLibrary asks of a
 * target language; so is a Java stack overflow ({@link #stackOverflow}).
 */
public final class Foreign {

    private Foreign() {
    }

    /** Whether {@code value} is a foreign value: not a Scheme value (design.md D10, D20). */
    public static boolean isForeign(Object value) {
        return !InteropValues.isSchemeValue(value);
    }

    /**
     * Whether {@code value} is a foreign executable: a procedure to Scheme (design.md D20). A
     * Scheme value is answered without interop.
     */
    public static boolean isProcedure(Object value) {
        return value instanceof TruffleObject && isForeign(value) && isExecutable(value);
    }

    /** Whether {@code value} is a foreign exception: an error object to Scheme (design.md D20). */
    public static boolean isException(Object value) {
        return value instanceof TruffleObject && isForeign(value) && isInteropException(value);
    }

    @TruffleBoundary
    private static boolean isExecutable(Object value) {
        return InteropLibrary.getUncached().isExecutable(value);
    }

    @TruffleBoundary
    private static boolean isInteropException(Object value) {
        return InteropLibrary.getUncached().isException(value);
    }

    /** A foreign exception's message, as {@code error-object-message} returns it: a new string. */
    @TruffleBoundary
    public static Object exceptionMessage(Object exception) {
        InteropLibrary interop = InteropLibrary.getUncached();
        try {
            if (interop.hasExceptionMessage(exception)) {
                return SString.of(interop.asString(interop.getExceptionMessage(exception)));
            }
            return SString.of(interop.asString(interop.toDisplayString(exception, true)));
        } catch (UnsupportedMessageException e) {
            return SString.of("");
        }
    }

    /**
     * Enters a foreign operation: it counts as a foreign call on the stack, and its Java frames
     * count toward the stack use as a prompt's do ({@link StackBudget}). Returns the stack use to
     * give back to {@link #exit}.
     */
    public static int enter(SchemeContext context) {
        int use = context.stackUse;
        context.stackUse = use + StackBudget.promptCost(context);
        context.foreignDepth++;
        return use;
    }

    /** Leaves the foreign operation that {@link #enter} returned {@code use} for. */
    public static void exit(SchemeContext context, int use) {
        context.foreignDepth--;
        context.stackUse = use;
    }

    // ------------------------------------------------------------------------------------------
    // The operations of (graalvm polyglot) (design.md D20). Builtins only call these, and the
    // foreign-* procedures' nodes (ForeignNodes): the interop that may run foreign code, and through
    // it Scheme callbacks under prompts of their own, is here in the runtime (AGENTS.md rule 1).

    /** {@code (polyglot-eval language source)}: through {@code Env.parsePublic}. */
    @TruffleBoundary
    public static Object eval(String language, String text) {
        TruffleLanguage.Env env = SchemeContext.get(null).getEnv();
        checkEvalAllowed("polyglot-eval", env, language);
        return evaluate(env, Source.newBuilder(language, text, "<polyglot-eval>").build());
    }

    /** {@code (polyglot-eval-file language path)}: a file the Env can read, through {@code parsePublic}. */
    @TruffleBoundary
    public static Object evalFile(String language, String path) {
        TruffleLanguage.Env env = SchemeContext.get(null).getEnv();
        checkEvalAllowed("polyglot-eval-file", env, language);
        Source source;
        try {
            source = Source.newBuilder(language, env.getPublicTruffleFile(path)).build();
        } catch (IOException | SecurityException | UnsupportedOperationException | IllegalArgumentException e) {
            throw new SchemeError(new SErrorObject(SString.of("polyglot-eval-file: can't read the file"), new SPair(SString.of(path), SConstant.EMPTY_LIST),
                            SErrorObject.Kind.FILE));
        }
        return evaluate(env, source);
    }

    private static Object evaluate(TruffleLanguage.Env env, Source source) {
        SchemeContext context = SchemeContext.get(null);
        int use = enter(context);
        try {
            return incoming(env.parsePublic(source).call());
        } catch (AbstractTruffleException e) {
            throw raised(e);
        } catch (StackOverflowError e) {
            throw stackOverflow();
        } finally {
            exit(context, use);
        }
    }

    /** {@code (polyglot-language-available? language)}: whether polyglot-eval may evaluate it. */
    @TruffleBoundary
    public static boolean isLanguageAvailable(String language) {
        TruffleLanguage.Env env = SchemeContext.get(null).getEnv();
        LanguageInfo info = env.getPublicLanguages().get(language);
        return info != null && env.isPolyglotEvalAllowed(info);
    }

    /** The error unless polyglot-eval may evaluate {@code language}, as parsePublic asks. */
    private static void checkEvalAllowed(String who, TruffleLanguage.Env env, String language) {
        LanguageInfo info = env.getPublicLanguages().get(language);
        if (info != null && env.isPolyglotEvalAllowed(info)) {
            return;
        }
        Object available = SConstant.EMPTY_LIST;
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, LanguageInfo> e : env.getPublicLanguages().entrySet()) {
            if (env.isPolyglotEvalAllowed(e.getValue())) {
                ids.add(e.getKey());
            }
        }
        ids.sort(null);
        for (int i = ids.size() - 1; i >= 0; i--) {
            available = new SPair(SString.of(ids.get(i)), available);
        }
        throw new SchemeError(new SErrorObject(SString.of(who + ": language not available (the ones that are follow it)"),
                        new SPair(SString.of(language), new SPair(available, SConstant.EMPTY_LIST)), SErrorObject.Kind.OTHER));
    }

    /**
     * {@code (polyglot-import name [default])}: the binding's value, or {@code unbound} if there is
     * none, as other languages answer their null for it (design.md D20, "Decided on review").
     */
    @TruffleBoundary
    public static Object importSymbol(String name, Object unbound) {
        TruffleLanguage.Env env = checkBindings("polyglot-import");
        SchemeContext context = SchemeContext.get(null);
        int use = enter(context);
        try {
            Object value = env.importSymbol(name);
            return value == null ? unbound : incoming(value);
        } catch (AbstractTruffleException e) {
            throw raised(e);
        } catch (StackOverflowError e) {
            throw stackOverflow();
        } finally {
            exit(context, use);
        }
    }

    /** {@code (polyglot-export name value)}. */
    @TruffleBoundary
    public static void exportSymbol(String name, Object value) {
        TruffleLanguage.Env env = checkBindings("polyglot-export");
        SchemeContext context = SchemeContext.get(null);
        int use = enter(context);
        try {
            env.exportSymbol(name, value);
        } catch (AbstractTruffleException e) {
            throw raised(e);
        } catch (StackOverflowError e) {
            throw stackOverflow();
        } finally {
            exit(context, use);
        }
    }

    private static TruffleLanguage.Env checkBindings(String who) {
        TruffleLanguage.Env env = SchemeContext.get(null).getEnv();
        if (!env.isPolyglotBindingsAccessAllowed()) {
            throw new SchemeError(who + ": the context doesn't allow access to the polyglot bindings (Context.Builder.allowPolyglotAccess)");
        }
        return env;
    }

    /**
     * {@code (java-type name)}: through {@code Env.lookupHostSymbol}, as the context's class filter
     * and {@code HostAccess} allow, under every sandbox policy (design.md D20, "Permissions and the
     * sandbox").
     */
    @TruffleBoundary
    public static Object javaType(String name) {
        TruffleLanguage.Env env = SchemeContext.get(null).getEnv();
        if (!env.isHostLookupAllowed()) {
            throw new SchemeError("java-type: the context doesn't allow host class lookup (Context.Builder.allowHostClassLookup): " + name);
        }
        SchemeContext context = SchemeContext.get(null);
        int use = enter(context);
        Object type;
        try {
            type = env.lookupHostSymbol(name);
        } catch (AbstractTruffleException | IllegalArgumentException | SecurityException e) {
            throw new SchemeError(new SErrorObject(SString.of("java-type: no such class, or not accessible: " + e.getMessage()), new SPair(SString.of(name), SConstant.EMPTY_LIST),
                            SErrorObject.Kind.OTHER));
        } finally {
            exit(context, use);
        }
        if (ImageInfo.inImageRuntimeCode() && withoutReflectionMetadata(type)) {
            throw new SchemeError(new SErrorObject(SString.of("java-type: this native image has no reflection metadata for the class (reachability-metadata.json), so it has none of its " +
                            "constructors, methods or fields"), new SPair(SString.of(name), SConstant.EMPTY_LIST), SErrorObject.Kind.OTHER));
        }
        return type;
    }

    /**
     * Whether {@code type}, a class that a native image found, is one of the image's own classes of
     * which reflection, and so host interop, sees no public constructor, method (but
     * {@code Object}'s) or field: the image was built without reflection metadata for it (design.md
     * D20, "The launchers"). A class of a polyglot isolate's host is in the host's heap
     * ({@link HeapIsolationException}), with its members on the host's JVM, and is never one; nor is
     * an interface, which may legitimately have none.
     */
    private static boolean withoutReflectionMetadata(Object type) {
        InteropLibrary interop = InteropLibrary.getUncached();
        Object host;
        try {
            host = interop.isHostObject(type) ? interop.asHostObject(type) : null;
        } catch (HeapIsolationException | UnsupportedMessageException e) {
            return false;
        }
        if (!(host instanceof Class<?> c) || c.isInterface() || c.isArray() || c.isPrimitive()) {
            return false;
        }
        try {
            if (c.getConstructors().length > 0 || c.getFields().length > 0) {
                return false;
            }
            for (Method m : c.getMethods()) {
                if (m.getDeclaringClass() != Object.class) {
                    return false;
                }
            }
            return true;
        } catch (LinkageError e) {
            // A native image built to throw on missing metadata (MissingReflectionRegistrationError).
            return true;
        }
    }

    /**
     * The error for a member name that is neither a string nor a symbol (the {@code foreign-*}
     * procedures, {@link ForeignNodes}).
     */
    @TruffleBoundary
    public static SchemeError badMemberName(String who, Object receiver, Object key) {
        return SchemeError.argument(who, 2, "member name (a string or a symbol)", key);
    }

    /** A hash key as it goes out: a symbol as its name, since symbols aren't interop strings. */
    @TruffleBoundary
    public static Object hashKey(Object key) {
        return key instanceof SSymbol ? SString.of(key.toString()) : key;
    }

    /** A value that came in from foreign code, as Scheme sees it (design.md D10, D20). */
    @TruffleBoundary
    public static Object incoming(Object value) {
        try {
            return InteropValues.toScheme(value);
        } catch (UnsupportedTypeException e) {
            throw new SchemeError(String.valueOf(e.getMessage()));
        }
    }

    /**
     * What an exception that left foreign code raises at the Scheme call site (design.md D20): a
     * Scheme error that came back through the foreign frames raises what Scheme raised, and any
     * other guest exception raises itself, as a foreign value. Exits and interrupts go on as they
     * are.
     */
    @TruffleBoundary
    public static AbstractTruffleException raised(AbstractTruffleException e) {
        if (e instanceof SchemeError s) {
            // Uncaught in the callback (or a syntax error of polyglot-eval's Scheme): raised here.
            return s.isRaisable() ? s : SchemeError.raising(s.raised, s.getMessage());
        }
        try {
            ExceptionType type = InteropLibrary.getUncached().getExceptionType(e);
            if (type == ExceptionType.EXIT || type == ExceptionType.INTERRUPT) {
                return e;
            }
        } catch (UnsupportedMessageException ignored) {
            // Not an interop exception after all: raise it as it is.
        }
        return SchemeError.raising(e, String.valueOf(e.getMessage()));
    }

    /**
     * The Scheme error for a Java stack overflow that left a foreign operation (design.md D20,
     * "Stack depth across foreign frames"), as GraalVM's languages turn one into an error of their
     * own: Scheme spills its own frames, but can't spill the foreign frames between them, so a
     * recursion through another language overflows the Java stack. Raised at the operation's call
     * site, where {@code guard} catches it once the stack has unwound to it; an overflow while it is
     * made leaves for the next foreign operation out, which makes it again.
     */
    @TruffleBoundary
    public static SchemeError stackOverflow() {
        return new SchemeError("stack overflow: too many nested calls through other languages or Java");
    }

    /**
     * The exception an uncaught error leaves a root prompt with (design.md D20): a foreign
     * exception that nothing in Scheme handled leaves as itself, so the language or embedder that
     * called Scheme gets its own exception back; any other is {@code error}.
     */
    public static RuntimeException leaving(SchemeError error) {
        if (error.getUncaught() instanceof AbstractTruffleException e && !(e instanceof SchemeError)) {
            return e;
        }
        return error;
    }

    /** An interop error of a call of {@code procedure} (a foreign executable) as a Scheme error. */
    @TruffleBoundary
    public static SchemeError interopError(String who, Object procedure, InteropException e) {
        return interopError(who, 0, procedure, null, e);
    }

    /** An interop error of operation {@code who} on {@code receiver}, its argument number {@code position}. */
    @TruffleBoundary
    public static SchemeError interopError(String who, int position, Object receiver, InteropException e) {
        return interopError(who, position, receiver, null, e);
    }

    /**
     * An interop error of operation {@code who} on {@code receiver}, its argument number
     * {@code position} (0 for none), as a Scheme error (design.md D8, "Messages", D20): the
     * argument after it, {@code key} if not null, is the member, the index or the key the receiver
     * hasn't.
     * InteropLibrary asks a target language to translate each one into an error of its own.
     */
    @TruffleBoundary
    public static SchemeError interopError(String who, int position, Object receiver, Object key, InteropException e) {
        String argument = who + ": argument " + (position + 1);
        if (e instanceof ArityException a) {
            int min = a.getExpectedMinArity();
            int max = a.getExpectedMaxArity();
            String expected = max == min ? Integer.toString(min) : max < 0 ? "at least " + min : min + " to " + max;
            return SchemeError.of(who + ": expected " + expected + " arguments, got " + a.getActualArity(), receiver);
        } else if (e instanceof UnknownIdentifierException u && position > 0) {
            return SchemeError.of(argument + " is not a member of argument " + position, key != null ? key : SString.of(u.getUnknownIdentifier()));
        } else if (e instanceof InvalidArrayIndexException i && position > 0) {
            return SchemeError.of(argument + " is not an index of argument " + position, key != null ? key : i.getInvalidIndex());
        } else if (e instanceof UnknownKeyException && position > 0) {
            return SchemeError.of(argument + " is not a key of argument " + position, key != null ? key : receiver);
        } else if (e instanceof UnsupportedTypeException) {
            return SchemeError.of(who + ": unsupported argument types" + (e.getMessage() == null ? "" : " (" + e.getMessage() + ")"), receiver);
        } else if (position > 0) {
            return SchemeError.argument(who, position, "value that supports " + who, receiver);
        }
        return SchemeError.of(who + ": not supported by this foreign value", receiver);
    }
}
