package scheme.builtins;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.runtime.Foreign;
import scheme.runtime.PrimitiveNode;
import scheme.runtime.PrimitiveSpec;
import scheme.runtime.PrimitiveSpec.Result;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SString;
import scheme.runtime.value.SSymbol;
import scheme.runtime.value.SVector;

/**
 * The library {@code (graalvm polyglot)} (design.md D20): other GraalVM languages, the polyglot
 * bindings, foreign values and Java classes. Not an R7RS area: every name is a {@code %}-name, which
 * the library renames as it exports it, so nothing but a program that imports the library sees
 * these. Each primitive only checks its arguments and calls {@link Foreign}, in the runtime, which
 * makes the interop calls (AGENTS.md rule 1: foreign code may call Scheme back, but only under a
 * prompt of its own, a callback's).
 */
final class PolyglotPrimitives {

    private PolyglotPrimitives() {
    }

    /** This library's primitives, as {@code Builtins} registers them. */
    static List<PrimitiveSpec> primitives() {
        List<PrimitiveSpec> specs = new ArrayList<>();
        for (Primitive primitive : Primitive.values()) {
            specs.add(primitive.spec);
        }
        return specs;
    }

    /** The primitives: each constant has its name and arity, and makes its node ({@link Op}). */
    enum Primitive implements Supplier<PrimitiveNode> {
        EVAL("%polyglot-eval", 2, 2, Result.NEW_OBJECT),
        EVAL_FILE("%polyglot-eval-file", 2, 2, Result.NEW_OBJECT),
        LANGUAGE_AVAILABLE("%polyglot-language-available?", 1, 1, Result.VALUE),
        IMPORT("%polyglot-import", 1, 1, Result.NEW_OBJECT),
        EXPORT("%polyglot-export", 2, 2, Result.VALUE),
        JAVA_TYPE("%java-type", 1, 1, Result.VALUE),
        IS_FOREIGN("%foreign?", 1, 1, Result.VALUE),
        IS_NULL("%foreign-null?", 1, 1, Result.VALUE),
        REF("%foreign-ref", 2, 2, Result.NEW_OBJECT),
        SET("%foreign-set!", 3, 3, Result.VALUE),
        LENGTH("%foreign-length", 1, 1, Result.VALUE),
        KEYS("%foreign-keys", 1, 1, Result.NEW_LIST),
        TO_LIST("%foreign->list", 1, 1, Result.NEW_LIST),
        TO_VECTOR("%foreign->vector", 1, 1, Result.NEW_OBJECT),
        INVOKE("%foreign-invoke", 2, -1, Result.NEW_OBJECT),
        NEW("%foreign-new", 1, -1, Result.NEW_OBJECT),
        IS_INSTANCE("%foreign-instance?", 2, 2, Result.VALUE);

        final PrimitiveSpec spec;

        Primitive(String name, int minArgs, int maxArgs, Result result) {
            this.spec = new PrimitiveSpec(name, minArgs, maxArgs, this, result);
        }

        @Override
        public PrimitiveNode get() {
            return new Op(this);
        }

        /** The name the library exports it as, for messages. */
        String publicName() {
            return spec.name().substring(1);
        }
    }

    /** Every primitive of the library: a switch on which one, each a call of {@link Foreign}. */
    static final class Op extends PrimitiveNode {
        private final Primitive op;

        Op(Primitive op) {
            this.op = op;
        }

        @Override
        public Object invoke(Object[] args) {
            return run(op, args);
        }

        /** Behind a boundary: every case crosses into interop, and names are Java strings. */
        @TruffleBoundary
        private static Object run(Primitive op, Object[] args) {
            switch (op) {
                case EVAL:
                    return Foreign.eval(name(op, args[1]), text(op, args[2]));
                case EVAL_FILE:
                    return Foreign.evalFile(name(op, args[1]), text(op, args[2]));
                case LANGUAGE_AVAILABLE:
                    return Foreign.isLanguageAvailable(name(op, args[1]));
                case IMPORT:
                    return Foreign.importSymbol(name(op, args[1]));
                case EXPORT:
                    Foreign.exportSymbol(name(op, args[1]), args[2]);
                    return SConstant.UNSPECIFIED;
                case JAVA_TYPE:
                    return Foreign.javaType(text(op, args[1]));
                case IS_FOREIGN:
                    return Foreign.isForeign(args[1]);
                case IS_NULL:
                    return Foreign.isNull(args[1]);
                case REF:
                    return Foreign.ref(args[1], args[2]);
                case SET:
                    Foreign.set(args[1], args[2], args[3]);
                    return SConstant.UNSPECIFIED;
                case LENGTH:
                    return Foreign.length(args[1]);
                case KEYS:
                    return Foreign.keys(args[1]);
                case TO_LIST:
                    return list(Foreign.elements(op.publicName(), args[1]));
                case TO_VECTOR:
                    return new SVector(Foreign.elements(op.publicName(), args[1]), false);
                case INVOKE:
                    return Foreign.invoke(args[1], args[2], args, 3);
                case NEW:
                    return Foreign.instantiate(args[1], args, 2);
                case IS_INSTANCE:
                    return Foreign.isInstance(args[1], args[2]);
            }
            throw new AssertionError(op);
        }

        /** A language id or a binding name: a string or a symbol. */
        private static String name(Primitive op, Object value) {
            if (value instanceof SString || value instanceof SSymbol) {
                return value.toString();
            }
            throw ControlPrimitives.notA(op.publicName(), "string or symbol", value);
        }

        private static String text(Primitive op, Object value) {
            if (value instanceof SString) {
                return value.toString();
            }
            throw ControlPrimitives.notA(op.publicName(), "string", value);
        }

        private static Object list(Object[] items) {
            Object list = SConstant.EMPTY_LIST;
            for (int i = items.length - 1; i >= 0; i--) {
                list = new SPair(items[i], list);
            }
            return list;
        }
    }
}
