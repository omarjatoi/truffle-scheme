package scheme.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Exclusive;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.Introspectable;
import com.oracle.truffle.api.dsl.NeverDefault;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.Node;

import scheme.runtime.value.SString;
import scheme.runtime.value.SSymbol;

/**
 * The nodes that make foreign calls and bring foreign values in (design.md D20, "Foreign access in
 * compiled code"): each with an inline cache of {@link InteropLibrary}s keyed on the receiver, as
 * Truffle's interop guide asks of a language, so that compiled code calls the other language's own
 * message (an SL function's call, a Java field's read) and can inline it. Past {@link #LIMIT}
 * receivers, the DSL's uncached version of the specialization takes over, behind a boundary.
 *
 * <p>Like {@link Foreign}, these are the runtime's, not the builtins' (AGENTS.md rule 1): foreign
 * code may call Scheme back, under a root prompt of its own (D20, "Callbacks").
 */
public final class ForeignNodes {

    /** How many receivers (types of foreign value) an interop cache holds, as Truffle's own languages do. */
    static final String LIMIT = "3";

    private ForeignNodes() {
    }

    /**
     * The call of a call site, a trampoline or a prompt whose callee isn't a Scheme closure
     * (design.md D20, "Foreign executables are procedures"): a foreign executable is called through
     * interop with the arguments after index 0, counted as a foreign call on the stack
     * ({@link Foreign#enter}), and its result comes back as a Scheme value ({@link IncomingNode});
     * anything else is the "not a procedure" error. An interop error is a Scheme error, and an
     * exception the call throws is raised at the site ({@link Foreign#raised}).
     */
    @GenerateInline(false)
    @Introspectable
    public abstract static class CallNode extends Node {

        public abstract Object execute(Object procedure, Object[] arguments);

        @NeverDefault
        public static CallNode create() {
            return ForeignNodesFactory.CallNodeGen.create();
        }

        @Specialization(limit = LIMIT)
        static Object doCall(Object procedure, Object[] arguments,
                        @Bind Node node,
                        @CachedLibrary("procedure") InteropLibrary interop,
                        @Cached IncomingNode incoming) {
            if (!interop.isExecutable(procedure)) {
                throw SchemeRootNode.notAProcedure(procedure);
            }
            Object[] foreignArguments = new Object[arguments.length - 1];
            System.arraycopy(arguments, 1, foreignArguments, 0, foreignArguments.length);
            SchemeContext context = SchemeContext.get(node);
            int use = Foreign.enter(context);
            try {
                return incoming.execute(interop.execute(procedure, foreignArguments));
            } catch (InteropException e) {
                throw Foreign.interopError("call", procedure, e);
            } catch (AbstractTruffleException e) {
                throw Foreign.raised(e);
            } catch (StackOverflowError e) {
                throw Foreign.stackOverflow();
            } finally {
                Foreign.exit(context, use);
            }
        }
    }

    /**
     * A value that came in from foreign code, as Scheme sees it (design.md D10, D20): what
     * {@link InteropValues#toScheme} makes of it, with the common cases in compiled code. A
     * {@code long}, {@code double} or {@code boolean} is itself and an {@code int} a fixnum; a foreign
     * object that is a boolean or a number that fits in a {@code long} is read through its cached
     * library, and one that is none of a boolean, a string, a number or a Java object is itself.
     * The rest (strings, which become fresh Scheme strings, big numbers, Java objects, which keep
     * their identity through a table, and other Java primitives) go to {@code toScheme}, behind
     * its boundary.
     */
    @GenerateInline(false)
    @Introspectable
    public abstract static class IncomingNode extends Node {

        public abstract Object execute(Object value);

        @NeverDefault
        public static IncomingNode create() {
            return ForeignNodesFactory.IncomingNodeGen.create();
        }

        @Specialization
        static long doLong(long value) {
            return value;
        }

        @Specialization
        static double doDouble(double value) {
            return value;
        }

        @Specialization
        static boolean doBoolean(boolean value) {
            return value;
        }

        @Specialization
        static long doInt(int value) {
            return value;
        }

        @Specialization(guards = "isForeignObject(value)", limit = LIMIT)
        static Object doForeign(Object value,
                        @CachedLibrary("value") InteropLibrary interop) {
            try {
                if (interop.isBoolean(value)) {
                    return interop.asBoolean(value);
                }
                if (interop.isString(value)) {
                    return Foreign.incoming(value);
                }
                if (interop.isNumber(value)) {
                    return interop.fitsInLong(value) ? interop.asLong(value) : Foreign.incoming(value);
                }
                if (interop.isHostObject(value)) {
                    return Foreign.incoming(value);
                }
            } catch (UnsupportedMessageException e) {
                // The value said it was one, then refused: a bug in the other language.
                throw refused(e);
            }
            return value;
        }

        /**
         * Whether {@code value} is an interop object of another language or of Java: not a Scheme
         * value, nor an exception or a language view, which {@code toScheme} unwraps.
         */
        static boolean isForeignObject(Object value) {
            return value instanceof TruffleObject && !(value instanceof AbstractTruffleException) && !(value instanceof LanguageView) && !InteropValues.isSchemeValue(value);
        }

        @Fallback
        static Object doOther(Object value) {
            return Foreign.incoming(value);
        }

        @TruffleBoundary
        private static IllegalStateException refused(UnsupportedMessageException e) {
            return new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The foreign-* procedures of (graalvm polyglot), each a node of the primitive's (a call site's
    // own when it runs in place, design.md "Primitives run in place"). Each message that may run
    // foreign code is a foreign operation (Foreign.enter); its interop errors are Scheme errors of
    // the procedure's, and what it throws is raised (Foreign.raised).

    /** The arguments from index {@code from} on, as they go out. */
    static Object[] outgoing(Object[] arguments, int from) {
        Object[] out = new Object[arguments.length - from];
        System.arraycopy(arguments, from, out, 0, out.length);
        return out;
    }

    /** {@code key}'s member name, or the error of {@code who}. */
    static String memberName(MemberNameNode names, String who, Object receiver, Object key) {
        String name = names.execute(key);
        if (name == null) {
            throw Foreign.badMemberName(who, receiver, key);
        }
        return name;
    }

    /**
     * A member name: a string's or a symbol's; null for any other key. A symbol's is a constant of
     * the cache (a symbol's name can't change), so that the member's own cache in the other language
     * sees a constant name; a string's is read each time (a string can be changed).
     */
    @GenerateInline(false)
    @Introspectable
    public abstract static class MemberNameNode extends Node {

        abstract String execute(Object key);

        @NeverDefault
        static MemberNameNode create() {
            return ForeignNodesFactory.MemberNameNodeGen.create();
        }

        @Specialization(guards = "key == cachedKey", limit = LIMIT)
        static String doCachedSymbol(@SuppressWarnings("unused") SSymbol key,
                        @Cached("key") @SuppressWarnings("unused") SSymbol cachedKey,
                        @Cached("name(cachedKey)") String name) {
            return name;
        }

        @Specialization(replaces = "doCachedSymbol")
        static String doSymbol(SSymbol key) {
            return name(key);
        }

        @Specialization
        static String doString(SString key) {
            return name(key);
        }

        @Fallback
        static String doOther(@SuppressWarnings("unused") Object key) {
            return null;
        }

        @TruffleBoundary
        static String name(Object key) {
            return key.toString();
        }
    }

    /** {@code (foreign-ref obj key)}: an array element for an exact integer key, else a hash value, else a member. */
    @GenerateInline(false)
    @Introspectable
    public abstract static class ReadNode extends Node {

        private static final String WHO = "foreign-ref";

        public abstract Object execute(Object receiver, Object key);

        @NeverDefault
        public static ReadNode create() {
            return ForeignNodesFactory.ReadNodeGen.create();
        }

        @Specialization(guards = "interop.hasArrayElements(receiver)", limit = LIMIT)
        static Object doElement(Object receiver, long index,
                        @Bind Node node,
                        @CachedLibrary("receiver") InteropLibrary interop,
                        @Exclusive @Cached IncomingNode incoming) {
            SchemeContext context = SchemeContext.get(node);
            int use = Foreign.enter(context);
            try {
                return incoming.execute(interop.readArrayElement(receiver, index));
            } catch (InteropException e) {
                throw Foreign.interopError(WHO, receiver, e);
            } catch (AbstractTruffleException e) {
                throw Foreign.raised(e);
            } catch (StackOverflowError e) {
                throw Foreign.stackOverflow();
            } finally {
                Foreign.exit(context, use);
            }
        }

        @Specialization(guards = "!isElement(interop, receiver, key)", limit = LIMIT)
        static Object doKey(Object receiver, Object key,
                        @Bind Node node,
                        @CachedLibrary("receiver") InteropLibrary interop,
                        @Cached MemberNameNode names,
                        @Exclusive @Cached IncomingNode incoming) {
            SchemeContext context = SchemeContext.get(node);
            int use = Foreign.enter(context);
            try {
                if (interop.hasHashEntries(receiver)) {
                    return incoming.execute(interop.readHashValue(receiver, Foreign.hashKey(key)));
                }
                return incoming.execute(interop.readMember(receiver, memberName(names, WHO, receiver, key)));
            } catch (InteropException e) {
                throw Foreign.interopError(WHO, receiver, e);
            } catch (AbstractTruffleException e) {
                throw Foreign.raised(e);
            } catch (StackOverflowError e) {
                throw Foreign.stackOverflow();
            } finally {
                Foreign.exit(context, use);
            }
        }

        /** Whether {@code key} is an array element's index of {@code receiver}'s. */
        static boolean isElement(InteropLibrary interop, Object receiver, Object key) {
            return key instanceof Long && interop.hasArrayElements(receiver);
        }
    }

    /** {@code (foreign-set! obj key value)}: as {@link ReadNode} reads. */
    @GenerateInline(false)
    @Introspectable
    public abstract static class WriteNode extends Node {

        private static final String WHO = "foreign-set!";

        public abstract void execute(Object receiver, Object key, Object value);

        @NeverDefault
        public static WriteNode create() {
            return ForeignNodesFactory.WriteNodeGen.create();
        }

        @Specialization(guards = "interop.hasArrayElements(receiver)", limit = LIMIT)
        static void doElement(Object receiver, long index, Object value,
                        @Bind Node node,
                        @CachedLibrary("receiver") InteropLibrary interop) {
            SchemeContext context = SchemeContext.get(node);
            int use = Foreign.enter(context);
            try {
                interop.writeArrayElement(receiver, index, value);
            } catch (InteropException e) {
                throw Foreign.interopError(WHO, receiver, e);
            } catch (AbstractTruffleException e) {
                throw Foreign.raised(e);
            } catch (StackOverflowError e) {
                throw Foreign.stackOverflow();
            } finally {
                Foreign.exit(context, use);
            }
        }

        @Specialization(guards = "!isElement(interop, receiver, key)", limit = LIMIT)
        static void doKey(Object receiver, Object key, Object value,
                        @Bind Node node,
                        @CachedLibrary("receiver") InteropLibrary interop,
                        @Cached MemberNameNode names) {
            SchemeContext context = SchemeContext.get(node);
            int use = Foreign.enter(context);
            try {
                if (interop.hasHashEntries(receiver)) {
                    interop.writeHashEntry(receiver, Foreign.hashKey(key), value);
                } else {
                    interop.writeMember(receiver, memberName(names, WHO, receiver, key), value);
                }
            } catch (InteropException e) {
                throw Foreign.interopError(WHO, receiver, e);
            } catch (AbstractTruffleException e) {
                throw Foreign.raised(e);
            } catch (StackOverflowError e) {
                throw Foreign.stackOverflow();
            } finally {
                Foreign.exit(context, use);
            }
        }

        /** Whether {@code key} is an array element's index of {@code receiver}'s. */
        static boolean isElement(InteropLibrary interop, Object receiver, Object key) {
            return key instanceof Long && interop.hasArrayElements(receiver);
        }
    }

    /** {@code (foreign-length obj)}: an array's size, or a hash's. */
    @GenerateInline(false)
    @Introspectable
    public abstract static class LengthNode extends Node {

        public abstract long execute(Object receiver);

        @NeverDefault
        public static LengthNode create() {
            return ForeignNodesFactory.LengthNodeGen.create();
        }

        @Specialization(limit = LIMIT)
        static long doLength(Object receiver,
                        @Bind Node node,
                        @CachedLibrary("receiver") InteropLibrary interop) {
            SchemeContext context = SchemeContext.get(node);
            int use = Foreign.enter(context);
            try {
                return interop.hasArrayElements(receiver) ? interop.getArraySize(receiver) : interop.getHashSize(receiver);
            } catch (InteropException e) {
                throw Foreign.interopError("foreign-length", receiver, e);
            } catch (AbstractTruffleException e) {
                throw Foreign.raised(e);
            } catch (StackOverflowError e) {
                throw Foreign.stackOverflow();
            } finally {
                Foreign.exit(context, use);
            }
        }
    }

    /** {@code (foreign-invoke obj member arg ...)}: the arguments are {@code arguments} from {@code from}. */
    @GenerateInline(false)
    @Introspectable
    public abstract static class InvokeNode extends Node {

        private static final String WHO = "foreign-invoke";

        public abstract Object execute(Object receiver, Object member, Object[] arguments, int from);

        @NeverDefault
        public static InvokeNode create() {
            return ForeignNodesFactory.InvokeNodeGen.create();
        }

        @Specialization(limit = LIMIT)
        static Object doInvoke(Object receiver, Object member, Object[] arguments, int from,
                        @Bind Node node,
                        @CachedLibrary("receiver") InteropLibrary interop,
                        @Cached MemberNameNode names,
                        @Cached IncomingNode incoming) {
            String name = memberName(names, WHO, receiver, member);
            Object[] foreignArguments = outgoing(arguments, from);
            SchemeContext context = SchemeContext.get(node);
            int use = Foreign.enter(context);
            try {
                return incoming.execute(interop.invokeMember(receiver, name, foreignArguments));
            } catch (InteropException e) {
                throw Foreign.interopError(WHO, receiver, e);
            } catch (AbstractTruffleException e) {
                throw Foreign.raised(e);
            } catch (StackOverflowError e) {
                throw Foreign.stackOverflow();
            } finally {
                Foreign.exit(context, use);
            }
        }
    }

    /** {@code (foreign-new constructor arg ...)}: the arguments are {@code arguments} from {@code from}. */
    @GenerateInline(false)
    @Introspectable
    public abstract static class InstantiateNode extends Node {

        public abstract Object execute(Object constructor, Object[] arguments, int from);

        @NeverDefault
        public static InstantiateNode create() {
            return ForeignNodesFactory.InstantiateNodeGen.create();
        }

        @Specialization(limit = LIMIT)
        static Object doInstantiate(Object constructor, Object[] arguments, int from,
                        @Bind Node node,
                        @CachedLibrary("constructor") InteropLibrary interop,
                        @Cached IncomingNode incoming) {
            Object[] foreignArguments = outgoing(arguments, from);
            SchemeContext context = SchemeContext.get(node);
            int use = Foreign.enter(context);
            try {
                return incoming.execute(interop.instantiate(constructor, foreignArguments));
            } catch (InteropException e) {
                throw Foreign.interopError("foreign-new", constructor, e);
            } catch (AbstractTruffleException e) {
                throw Foreign.raised(e);
            } catch (StackOverflowError e) {
                throw Foreign.stackOverflow();
            } finally {
                Foreign.exit(context, use);
            }
        }
    }

    /** {@code (foreign-instance? obj type)}: {@code isMetaInstance}, cached on the type. */
    @GenerateInline(false)
    @Introspectable
    public abstract static class IsInstanceNode extends Node {

        public abstract boolean execute(Object value, Object type);

        @NeverDefault
        public static IsInstanceNode create() {
            return ForeignNodesFactory.IsInstanceNodeGen.create();
        }

        @Specialization(limit = LIMIT)
        static boolean doIsInstance(Object value, Object type,
                        @Bind Node node,
                        @CachedLibrary("type") InteropLibrary interop) {
            SchemeContext context = SchemeContext.get(node);
            int use = Foreign.enter(context);
            try {
                if (!interop.isMetaObject(type)) {
                    throw UnsupportedMessageException.create();
                }
                return interop.isMetaInstance(type, value);
            } catch (InteropException e) {
                throw Foreign.interopError("foreign-instance?", type, e);
            } catch (AbstractTruffleException e) {
                throw Foreign.raised(e);
            } catch (StackOverflowError e) {
                throw Foreign.stackOverflow();
            } finally {
                Foreign.exit(context, use);
            }
        }
    }

    /** {@code (foreign-null? obj)}: false of every Scheme value. */
    @GenerateInline(false)
    @Introspectable
    public abstract static class IsNullNode extends Node {

        public abstract boolean execute(Object value);

        @NeverDefault
        public static IsNullNode create() {
            return ForeignNodesFactory.IsNullNodeGen.create();
        }

        @Specialization(guards = "isForeign(value)", limit = LIMIT)
        static boolean doForeign(Object value,
                        @CachedLibrary("value") InteropLibrary interop) {
            return interop.isNull(value);
        }

        @Fallback
        static boolean doScheme(@SuppressWarnings("unused") Object value) {
            return false;
        }

        static boolean isForeign(Object value) {
            return Foreign.isForeign(value);
        }
    }

    /**
     * {@code (foreign-keys obj)}: a hash's keys, or an object's member names; and
     * {@code (foreign->list obj)} and {@code (foreign->vector obj)}: an array's elements, or an
     * iterable's. As an array, which the primitive makes a list or a vector of. The iterators and
     * the members' array are read through a library cache of their own.
     */
    @GenerateInline(false)
    @Introspectable
    public abstract static class ElementsNode extends Node {

        /** The most elements a Java array can hold. */
        private static final int MAX_ELEMENTS = Integer.MAX_VALUE - 8;

        /** The keys if {@code keys}, else the elements; {@code who} names the procedure. */
        public abstract Object[] execute(String who, boolean keys, Object receiver);

        @NeverDefault
        public static ElementsNode create() {
            return ForeignNodesFactory.ElementsNodeGen.create();
        }

        @Specialization(limit = LIMIT)
        static Object[] doElements(String who, boolean keys, Object receiver,
                        @Bind Node node,
                        @CachedLibrary("receiver") InteropLibrary interop,
                        @CachedLibrary(limit = LIMIT) InteropLibrary items,
                        @Cached IncomingNode incoming) {
            SchemeContext context = SchemeContext.get(node);
            int use = Foreign.enter(context);
            try {
                if (keys) {
                    if (interop.hasHashEntries(receiver)) {
                        return iterate(who, interop.getHashKeysIterator(receiver), items, incoming);
                    }
                    return read(who, interop.getMembers(receiver), items, incoming);
                }
                if (interop.hasArrayElements(receiver)) {
                    return read(who, receiver, interop, incoming);
                }
                return iterate(who, interop.getIterator(receiver), items, incoming);
            } catch (InteropException e) {
                throw Foreign.interopError(who, receiver, e);
            } catch (AbstractTruffleException e) {
                throw Foreign.raised(e);
            } catch (StackOverflowError e) {
                throw Foreign.stackOverflow();
            } finally {
                Foreign.exit(context, use);
            }
        }

        /** An array's elements, through {@code interop}. */
        private static Object[] read(String who, Object array, InteropLibrary interop, IncomingNode incoming) throws InteropException {
            long size = interop.getArraySize(array);
            if (size > MAX_ELEMENTS) {
                throw tooMany(who);
            }
            Object[] result = new Object[(int) size];
            for (int i = 0; i < result.length; i++) {
                result[i] = incoming.execute(interop.readArrayElement(array, i));
            }
            return result;
        }

        /** What an iterator gives, through {@code interop}. */
        private static Object[] iterate(String who, Object iterator, InteropLibrary interop, IncomingNode incoming) throws InteropException {
            Object[] result = new Object[8];
            int size = 0;
            while (interop.hasIteratorNextElement(iterator)) {
                Object item = incoming.execute(interop.getIteratorNextElement(iterator));
                if (size == result.length) {
                    if (size > MAX_ELEMENTS / 2) {
                        throw tooMany(who);
                    }
                    Object[] larger = new Object[size * 2];
                    System.arraycopy(result, 0, larger, 0, size);
                    result = larger;
                }
                result[size++] = item;
            }
            Object[] exact = new Object[size];
            System.arraycopy(result, 0, exact, 0, size);
            return exact;
        }

        @TruffleBoundary
        private static SchemeError tooMany(String who) {
            return new SchemeError(who + ": too many elements");
        }
    }
}
