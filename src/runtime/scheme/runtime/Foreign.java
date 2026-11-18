package scheme.runtime;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.polyglot.SandboxPolicy;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.ExceptionType;
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
 * Scheme's side of foreign calls (design.md D20): calls of foreign executables, the count of
 * foreign calls on the stack that continuations are checked against, and how foreign exceptions and
 * interop errors become Scheme errors.
 *
 * <p>Every operation that enters another language or Java (a foreign call, a member read, an
 * {@code eval}) runs between {@link #enter} and {@link #exit}, which count it in
 * {@link SchemeContext#foreignDepth}: a callback that runs inside it sees the count, so a
 * continuation captured on one side of the foreign frames is never invoked on the other
 * ({@link Continuation#checkInvocable}). An exception the foreign code throws is caught as an
 * {@link AbstractTruffleException} and raised in Scheme at the call site ({@link #raised}); an
 * {@link InteropException} is a Scheme error ({@link #interopError}), as InteropLibrary asks of a
 * target language.
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

    /**
     * The call of a call site (or a prompt) whose callee isn't a Scheme closure: a foreign
     * executable is called through interop with the arguments after index 0, and its result comes
     * back as a Scheme value; anything else is the "not a procedure" error.
     */
    @TruffleBoundary
    public static Object call(Object procedure, Object[] arguments) {
        if (!isProcedure(procedure)) {
            throw SchemeRootNode.notAProcedure(procedure);
        }
        Object[] foreignArguments = Arrays.copyOfRange(arguments, 1, arguments.length);
        SchemeContext context = SchemeContext.get(null);
        int use = enter(context);
        try {
            return incoming(InteropLibrary.getUncached().execute(procedure, foreignArguments));
        } catch (InteropException e) {
            throw interopError("call", procedure, e);
        } catch (AbstractTruffleException e) {
            throw raised(e);
        } finally {
            exit(context, use);
        }
    }

    // ------------------------------------------------------------------------------------------
    // The operations of (graalvm polyglot) (design.md D20). Builtins only call these: the interop
    // that may run foreign code, and through it Scheme callbacks under prompts of their own, is here
    // in the runtime (AGENTS.md rule 1).

    /** An operation across the boundary, given the uncached library. */
    @FunctionalInterface
    private interface Operation {
        Object run(InteropLibrary interop) throws InteropException;
    }

    /**
     * Runs {@code operation} as a foreign operation ({@link #enter}): its interop errors are Scheme
     * errors of {@code who} on {@code receiver}, and what it throws is raised ({@link #raised}).
     */
    private static Object across(String who, Object receiver, Operation operation) {
        SchemeContext context = SchemeContext.get(null);
        int use = enter(context);
        try {
            return operation.run(InteropLibrary.getUncached());
        } catch (InteropException e) {
            throw interopError(who, receiver, e);
        } catch (AbstractTruffleException e) {
            throw raised(e);
        } finally {
            exit(context, use);
        }
    }

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

    /** {@code (polyglot-import name)}: the binding's value, or #f. */
    @TruffleBoundary
    public static Object importSymbol(String name) {
        TruffleLanguage.Env env = checkBindings("polyglot-import");
        SchemeContext context = SchemeContext.get(null);
        int use = enter(context);
        try {
            Object value = env.importSymbol(name);
            return value == null ? Boolean.FALSE : incoming(value);
        } catch (AbstractTruffleException e) {
            throw raised(e);
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

    /** {@code (java-type name)}: through {@code Env.lookupHostSymbol}, never under a sandbox. */
    @TruffleBoundary
    public static Object javaType(String name) {
        TruffleLanguage.Env env = SchemeContext.get(null).getEnv();
        SandboxPolicy policy = env.getSandboxPolicy();
        if (policy != SandboxPolicy.TRUSTED) {
            throw new SchemeError("java-type: host class lookup is refused under the " + policy + " sandbox policy");
        }
        if (ImageInfo.inImageRuntimeCode()) {
            // A native image has a Java class's constructors, methods and fields only if it was built
            // with reflection metadata for them, and ours has none (design.md D17): a class it found
            // would be one nothing can be done with.
            throw new SchemeError("java-type: Java classes are not available in a native image, which is built without their reflection metadata: " + name);
        }
        if (!env.isHostLookupAllowed()) {
            throw new SchemeError("java-type: the context doesn't allow host class lookup (Context.Builder.allowHostClassLookup): " + name);
        }
        SchemeContext context = SchemeContext.get(null);
        int use = enter(context);
        try {
            return env.lookupHostSymbol(name);
        } catch (AbstractTruffleException | IllegalArgumentException | SecurityException e) {
            throw new SchemeError(new SErrorObject(SString.of("java-type: no such class, or not accessible: " + e.getMessage()), new SPair(SString.of(name), SConstant.EMPTY_LIST),
                            SErrorObject.Kind.OTHER));
        } finally {
            exit(context, use);
        }
    }

    /** {@code (foreign-null? x)}. */
    @TruffleBoundary
    public static boolean isNull(Object value) {
        return isForeign(value) && InteropLibrary.getUncached().isNull(value);
    }

    /** {@code (foreign-ref obj key)}: an array element, a hash value or a member. */
    @TruffleBoundary
    public static Object ref(Object receiver, Object key) {
        return across("foreign-ref", receiver, interop -> {
            if (key instanceof Long index && interop.hasArrayElements(receiver)) {
                return incoming(interop.readArrayElement(receiver, index));
            }
            if (interop.hasHashEntries(receiver)) {
                return incoming(interop.readHashValue(receiver, hashKey(key)));
            }
            return incoming(interop.readMember(receiver, memberName("foreign-ref", receiver, key)));
        });
    }

    /** {@code (foreign-set! obj key value)}. */
    @TruffleBoundary
    public static void set(Object receiver, Object key, Object value) {
        across("foreign-set!", receiver, interop -> {
            if (key instanceof Long index && interop.hasArrayElements(receiver)) {
                interop.writeArrayElement(receiver, index, value);
            } else if (interop.hasHashEntries(receiver)) {
                interop.writeHashEntry(receiver, hashKey(key), value);
            } else {
                interop.writeMember(receiver, memberName("foreign-set!", receiver, key), value);
            }
            return null;
        });
    }

    /** {@code (foreign-length obj)}: an array's size or a hash's. */
    @TruffleBoundary
    public static long length(Object receiver) {
        return (long) across("foreign-length", receiver, interop -> {
            if (interop.hasArrayElements(receiver)) {
                return interop.getArraySize(receiver);
            }
            return interop.getHashSize(receiver);
        });
    }

    /** {@code (foreign-keys obj)}: a hash's keys, or an object's member names, as a list. */
    @TruffleBoundary
    public static Object keys(Object receiver) {
        return across("foreign-keys", receiver, interop -> {
            List<Object> keys = new ArrayList<>();
            if (interop.hasHashEntries(receiver)) {
                Object iterator = interop.getHashKeysIterator(receiver);
                while (interop.hasIteratorNextElement(iterator)) {
                    keys.add(incoming(interop.getIteratorNextElement(iterator)));
                }
            } else {
                Object members = interop.getMembers(receiver);
                long size = interop.getArraySize(members);
                for (long i = 0; i < size; i++) {
                    keys.add(incoming(interop.readArrayElement(members, i)));
                }
            }
            return list(keys);
        });
    }

    /** {@code (foreign->list obj)} and {@code (foreign->vector obj)}: an array's or an iterable's elements. */
    @TruffleBoundary
    public static Object[] elements(String who, Object receiver) {
        return (Object[]) across(who, receiver, interop -> {
            List<Object> items = new ArrayList<>();
            if (interop.hasArrayElements(receiver)) {
                long size = interop.getArraySize(receiver);
                for (long i = 0; i < size; i++) {
                    items.add(incoming(interop.readArrayElement(receiver, i)));
                }
            } else {
                Object iterator = interop.getIterator(receiver);
                while (interop.hasIteratorNextElement(iterator)) {
                    items.add(incoming(interop.getIteratorNextElement(iterator)));
                }
            }
            return items.toArray();
        });
    }

    /** {@code (foreign-invoke obj member arg ...)}: the arguments are {@code arguments} from {@code from}. */
    @TruffleBoundary
    public static Object invoke(Object receiver, Object member, Object[] arguments, int from) {
        String name = memberName("foreign-invoke", receiver, member);
        Object[] foreignArguments = Arrays.copyOfRange(arguments, from, arguments.length);
        return across("foreign-invoke", receiver, interop -> incoming(interop.invokeMember(receiver, name, foreignArguments)));
    }

    /** {@code (foreign-new constructor arg ...)}: the arguments are {@code arguments} from {@code from}. */
    @TruffleBoundary
    public static Object instantiate(Object constructor, Object[] arguments, int from) {
        Object[] foreignArguments = Arrays.copyOfRange(arguments, from, arguments.length);
        return across("foreign-new", constructor, interop -> incoming(interop.instantiate(constructor, foreignArguments)));
    }

    /** {@code (foreign-instance? obj type)}: {@code isMetaInstance}. */
    @TruffleBoundary
    public static boolean isInstance(Object value, Object type) {
        return (boolean) across("foreign-instance?", type, interop -> interop.isMetaObject(type) ? interop.isMetaInstance(type, value) : unsupported());
    }

    private static Object unsupported() throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    /** A member name: a string's or a symbol's. */
    private static String memberName(String who, Object receiver, Object key) {
        if (key instanceof SString || key instanceof SSymbol) {
            return key.toString();
        }
        throw new SchemeError(new SErrorObject(SString.of(who + ": a member name is a string or a symbol"), new SPair(receiver, new SPair(key, SConstant.EMPTY_LIST)),
                        SErrorObject.Kind.OTHER));
    }

    /** A hash key as it goes out: a symbol as its name, since symbols aren't interop strings. */
    private static Object hashKey(Object key) {
        return key instanceof SSymbol ? SString.of(key.toString()) : key;
    }

    private static Object list(List<Object> items) {
        Object list = SConstant.EMPTY_LIST;
        for (int i = items.size() - 1; i >= 0; i--) {
            list = new SPair(items.get(i), list);
        }
        return list;
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

    /**
     * An interop error of operation {@code who} on {@code receiver} as a Scheme error (design.md
     * D8, D20): InteropLibrary asks a target language to translate each one into an error of its
     * own.
     */
    @TruffleBoundary
    public static SchemeError interopError(String who, Object receiver, InteropException e) {
        String message;
        if (e instanceof ArityException a) {
            message = who + ": wrong number of arguments: expected " + a.getExpectedMinArity() +
                            (a.getExpectedMaxArity() == a.getExpectedMinArity() ? "" : a.getExpectedMaxArity() < 0 ? " or more" : " to " + a.getExpectedMaxArity()) +
                            ", got " + a.getActualArity();
        } else if (e instanceof UnknownIdentifierException u) {
            message = who + ": no such member: " + u.getUnknownIdentifier();
        } else if (e instanceof InvalidArrayIndexException i) {
            message = who + ": index out of range: " + i.getInvalidIndex();
        } else if (e instanceof UnknownKeyException) {
            message = who + ": no such key";
        } else if (e instanceof UnsupportedTypeException) {
            message = who + ": unsupported argument types" + (e.getMessage() == null ? "" : ": " + e.getMessage());
        } else {
            message = who + ": not supported by this foreign value";
        }
        return new SchemeError(new SErrorObject(SString.of(message), new SPair(receiver, SConstant.EMPTY_LIST), SErrorObject.Kind.OTHER));
    }
}
