package scheme.interop;

import java.util.List;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.LanguageInfo;
import com.oracle.truffle.api.source.Source;

import scheme.runtime.BuiltinLibrary;
import scheme.runtime.InteropValues;
import scheme.runtime.PrimitiveNode;
import scheme.runtime.PrimitiveSpec;
import scheme.runtime.SchemeContext;
import scheme.runtime.SchemeError;
import scheme.runtime.TailCall;
import scheme.runtime.value.SClosure;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SErrorObject;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SString;
import scheme.runtime.value.SSymbol;
import scheme.runtime.value.SVector;

/**
 * EXPLORATION (docs/interop-and-tools-exploration.md): the primitives of {@code (truffle interop)}:
 * Scheme code reaching Java (host access) and other Truffle languages through
 * {@link InteropLibrary}. Design.md D10 leaves this out of v1; this is what it would take.
 *
 * <p>Not in scheme.builtins, whose rule-1 check forbids InteropLibrary: an interop message can run
 * guest code. Here every call into foreign code is a <em>leaf</em> call from Scheme's point of view:
 * the foreign frames sit between this primitive and whatever they call back, and a Scheme procedure
 * they call back runs under a root prompt of its own ({@code InteropCallRootNode}), so no Scheme
 * continuation ever has to capture a Java frame (design.md D5). The one message that could run a
 * Scheme procedure directly, {@code execute} on a Scheme procedure, is never sent: foreign-execute
 * tail-calls it instead (rule 1).
 *
 * <p>Values cross as they are (every Scheme value answers interop as what it is); results come back
 * as Scheme values where there is one (numbers, strings, characters, booleans,
 * {@link InteropValues#toSchemeDeep}), and stay foreign otherwise. A host exception is raised in
 * Scheme as an error object whose irritant is the exception; an error a callback didn't handle comes
 * back out of the foreign frames and is raised again here.
 */
public final class InteropPrimitives implements BuiltinLibrary {

    private static final int LIMIT = 3;

    @Override
    public List<PrimitiveSpec> primitives() {
        return List.of(
                        new PrimitiveSpec("java-type", 1, 1, JavaType::new),
                        new PrimitiveSpec("java-new", 1, -1, JavaNew::new),
                        new PrimitiveSpec("java-invoke", 2, -1, JavaInvoke::new),
                        new PrimitiveSpec("java-field", 2, 2, JavaField::new),
                        new PrimitiveSpec("java-set-field!", 3, 3, JavaSetField::new),
                        new PrimitiveSpec("foreign?", 1, 1, IsForeign::new),
                        new PrimitiveSpec("foreign-null?", 1, 1, IsForeignNull::new),
                        new PrimitiveSpec("foreign-execute", 1, -1, ForeignExecute::new),
                        new PrimitiveSpec("foreign-length", 1, 1, ForeignLength::new),
                        new PrimitiveSpec("foreign-ref", 2, 2, ForeignRef::new),
                        new PrimitiveSpec("foreign->vector", 1, 1, ForeignToVector::new),
                        new PrimitiveSpec("polyglot-import", 1, 1, PolyglotImport::new),
                        new PrimitiveSpec("polyglot-export", 2, 2, PolyglotExport::new),
                        new PrimitiveSpec("polyglot-eval", 2, 2, PolyglotEval::new));
    }

    /** A primitive that sends interop messages. */
    abstract static class InteropNode extends PrimitiveNode {
        @Child InteropLibrary interop = InteropLibrary.getFactory().createDispatched(LIMIT);

        @Override
        public final Object execute(Object[] args) {
            try {
                Object result = doExecute(args);
                return result instanceof TailCall ? result : InteropValues.toSchemeDeep(result);
            } catch (InteropException e) {
                throw interopError(e);
            } catch (SchemeError e) {
                throw fromCallback(e);
            } catch (AbstractTruffleException e) {
                // A host or foreign exception: raise it in Scheme (design.md D8), as an error object
                // whose irritant is the exception itself, so a handler can inspect it.
                throw foreignError(e);
            }
        }

        abstract Object doExecute(Object[] args) throws InteropException;
    }

    /** {@code (java-type "java.lang.Math")}: a host class, with its static members and constructors. */
    static final class JavaType extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return lookup(SchemeContext.get(this).getEnv(), name(args[0]));
        }

        @TruffleBoundary
        private static Object lookup(TruffleLanguage.Env env, String name) {
            if (!env.isHostLookupAllowed()) {
                throw new SchemeError("java-type: host class lookup is not allowed (Context.Builder.allowHostClassLookup)");
            }
            Object type;
            try {
                type = env.lookupHostSymbol(name);
            } catch (RuntimeException e) {
                type = null;
            }
            if (type == null) {
                throw new SchemeError("java-type: no such class, or not accessible: " + name);
            }
            return type;
        }
    }

    /** {@code (java-new type arg ...)}: instantiates a host class (or any instantiable foreign value). */
    static final class JavaNew extends InteropNode {
        @Override
        Object doExecute(Object[] args) throws InteropException {
            return interop.instantiate(args[0], rest(args, 1));
        }
    }

    /** {@code (java-invoke obj "method" arg ...)}: invokes a member (a Java method, a JS method, ...). */
    static final class JavaInvoke extends InteropNode {
        @Override
        Object doExecute(Object[] args) throws InteropException {
            return interop.invokeMember(args[0], name(args[1]), rest(args, 2));
        }
    }

    /** {@code (java-field obj "name")}: reads a member: a field, a static field, or a method as a value. */
    static final class JavaField extends InteropNode {
        @Override
        Object doExecute(Object[] args) throws InteropException {
            return interop.readMember(args[0], name(args[1]));
        }
    }

    /** {@code (java-set-field! obj "name" value)}. */
    static final class JavaSetField extends InteropNode {
        @Override
        Object doExecute(Object[] args) throws InteropException {
            interop.writeMember(args[0], name(args[1]), args[2]);
            return SConstant.UNSPECIFIED;
        }
    }

    /** {@code (foreign? x)}: whether x is a value of the host or another language, not a Scheme value. */
    static final class IsForeign extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return !InteropValues.isSchemeValue(args[0]);
        }
    }

    /** {@code (foreign-null? x)}: whether x is a foreign null (Java's null, JavaScript's null and undefined). */
    static final class IsForeignNull extends PrimitiveNode {
        @Child InteropLibrary interop = InteropLibrary.getFactory().createDispatched(LIMIT);

        @Override
        public Object execute(Object[] args) {
            return !InteropValues.isSchemeValue(args[0]) && interop.isNull(args[0]);
        }
    }

    /**
     * {@code (foreign-execute f arg ...)}: calls a foreign executable: a Java functional interface or
     * method value, a JavaScript function. A Scheme procedure is tail-called instead (rule 1).
     */
    static final class ForeignExecute extends InteropNode {
        @Override
        Object doExecute(Object[] args) throws InteropException {
            if (args[0] instanceof SClosure) {
                Object[] all = args.clone();
                return new TailCall(args[0], all);
            }
            return interop.execute(args[0], rest(args, 1));
        }
    }

    /** {@code (foreign-length array)}: the size of a foreign array (a Java array or List, a JS array). */
    static final class ForeignLength extends InteropNode {
        @Override
        Object doExecute(Object[] args) throws InteropException {
            return interop.getArraySize(args[0]);
        }
    }

    /** {@code (foreign-ref array i)}. */
    static final class ForeignRef extends InteropNode {
        @Override
        Object doExecute(Object[] args) throws InteropException {
            if (!(args[1] instanceof Long i)) {
                throw new SchemeError("foreign-ref: not an exact integer index");
            }
            return interop.readArrayElement(args[0], i);
        }
    }

    /** {@code (foreign->vector array)}: a new Scheme vector of the array's elements, as Scheme values. */
    static final class ForeignToVector extends InteropNode {
        @Override
        Object doExecute(Object[] args) throws InteropException {
            return toVector(args[0], interop);
        }

        @TruffleBoundary
        private static SVector toVector(Object array, InteropLibrary interop) throws InteropException {
            long size = interop.getArraySize(array);
            Object[] elements = new Object[(int) size];
            for (int i = 0; i < elements.length; i++) {
                elements[i] = InteropValues.toSchemeDeep(interop.readArrayElement(array, i));
            }
            return new SVector(elements);
        }
    }

    /** {@code (polyglot-import "name")}: a value another language or the embedder exported. */
    static final class PolyglotImport extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return importSymbol(SchemeContext.get(this).getEnv(), name(args[0]));
        }

        @TruffleBoundary
        private static Object importSymbol(TruffleLanguage.Env env, String name) {
            if (!env.isPolyglotBindingsAccessAllowed()) {
                throw new SchemeError("polyglot-import: polyglot access is not allowed (Context.Builder.allowPolyglotAccess)");
            }
            Object value = env.importSymbol(name);
            if (value == null) {
                throw new SchemeError("polyglot-import: nothing exported as " + name);
            }
            return InteropValues.toSchemeDeep(value);
        }
    }

    /** {@code (polyglot-export "name" value)}: exports a Scheme value to the polyglot bindings. */
    static final class PolyglotExport extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            exportSymbol(SchemeContext.get(this).getEnv(), name(args[0]), args[1]);
            return SConstant.UNSPECIFIED;
        }

        @TruffleBoundary
        private static void exportSymbol(TruffleLanguage.Env env, String name, Object value) {
            if (!env.isPolyglotBindingsAccessAllowed()) {
                throw new SchemeError("polyglot-export: polyglot access is not allowed (Context.Builder.allowPolyglotAccess)");
            }
            env.exportSymbol(name, value);
        }
    }

    /**
     * {@code (polyglot-eval "js" "code")}: evaluates code in another installed language. A leaf call
     * like any other foreign call: its frames are another language's.
     */
    static final class PolyglotEval extends PrimitiveNode {
        @Child IndirectCallNode callNode = IndirectCallNode.create();

        @Override
        public Object execute(Object[] args) {
            CallTarget target = parse(SchemeContext.get(this).getEnv(), name(args[0]), name(args[1]));
            try {
                return InteropValues.toSchemeDeep(callNode.call(target));
            } catch (SchemeError e) {
                throw fromCallback(e);
            } catch (AbstractTruffleException e) {
                throw foreignError(e);
            }
        }

        @TruffleBoundary
        private static CallTarget parse(TruffleLanguage.Env env, String language, String code) {
            LanguageInfo info = env.getPublicLanguages().get(language);
            if (info == null) {
                throw new SchemeError("polyglot-eval: no such language: " + language);
            }
            if (!env.isPolyglotEvalAllowed(info)) {
                throw new SchemeError("polyglot-eval: evaluating " + language + " is not allowed (Context.Builder.allowPolyglotAccess)");
            }
            return env.parsePublic(Source.newBuilder(language, code, "polyglot-eval").build());
        }
    }

    // ------------------------------------------------------------------------------------------

    /** A member or class name: a string or a symbol. */
    @TruffleBoundary
    static String name(Object value) {
        if (value instanceof SString s) {
            return s.toString();
        }
        if (value instanceof SSymbol s) {
            return s.toString();
        }
        throw new SchemeError("not a string or symbol: " + InteropValues.display(value));
    }

    static Object[] rest(Object[] args, int from) {
        Object[] rest = new Object[args.length - from];
        System.arraycopy(args, from, rest, 0, rest.length);
        return rest;
    }

    @TruffleBoundary
    static SchemeError interopError(InteropException e) {
        String what;
        if (e instanceof UnknownIdentifierException u) {
            what = "no such member: " + u.getUnknownIdentifier();
        } else if (e instanceof ArityException a) {
            what = "wrong number of arguments: expected " + a.getExpectedMinArity() + (a.getExpectedMaxArity() == a.getExpectedMinArity() ? "" : " or more") + ", got " + a.getActualArity();
        } else if (e instanceof UnsupportedTypeException) {
            what = "an argument has the wrong type: " + e.getMessage();
        } else if (e instanceof InvalidArrayIndexException i) {
            what = "index out of range: " + i.getInvalidIndex();
        } else if (e instanceof UnsupportedMessageException) {
            what = "not supported by this value";
        } else {
            what = e.getMessage();
        }
        return new SchemeError("interop: " + what);
    }

    /**
     * An error from a Scheme procedure that foreign code called back: one the callback didn't handle
     * (uncaught under its own prompt) is raised again here, on this side of the foreign frames.
     */
    @TruffleBoundary
    static SchemeError fromCallback(SchemeError e) {
        if (e.isRaisable() || e.isSyntaxError()) {
            return e;
        }
        Object raised = e.getUncaught();
        if (raised instanceof SErrorObject object) {
            return new SchemeError(object);
        }
        // Prototype: SchemeError can only raise error objects, so wrap any other raised object.
        return new SchemeError(new SErrorObject(SString.of("raised in a callback"), new SPair(raised, SConstant.EMPTY_LIST), SErrorObject.Kind.OTHER));
    }

    @TruffleBoundary
    static SchemeError foreignError(AbstractTruffleException e) {
        return new SchemeError(new SErrorObject(SString.of(String.valueOf(e.getMessage())), new SPair(e, SConstant.EMPTY_LIST), SErrorObject.Kind.OTHER));
    }
}
