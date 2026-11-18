package scheme.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
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
}
