package scheme.builtins;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.runtime.Foreign;
import scheme.runtime.ForeignNodes;
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
            switch (this) {
                case IS_FOREIGN:
                    return new IsForeign();
                case IS_NULL:
                    return new IsNull();
                case REF:
                    return new Ref();
                case SET:
                    return new Set();
                case LENGTH:
                    return new Length();
                case KEYS:
                case TO_LIST:
                case TO_VECTOR:
                    return new Elements(this);
                case INVOKE:
                    return new Invoke();
                case NEW:
                    return new New();
                case IS_INSTANCE:
                    return new IsInstance();
                default:
                    return new Op(this);
            }
        }

        /** The name the library exports it as, for messages. */
        String publicName() {
            return spec.name().substring(1);
        }
    }

    /**
     * The primitives that reach the context's Env (other languages, the bindings, Java classes): a
     * switch on which one, each a call of {@link Foreign}, behind a boundary.
     */
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
                default:
                    // The foreign-* procedures are nodes of their own (below).
                    throw new AssertionError(op);
            }
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
    }

    // ---------------------------------------------------------------------------------------------
    // The foreign-* procedures: each a node of the runtime's (ForeignNodes), with an inline cache of
    // the interop libraries of the values it has seen, so that compiled code reads a member or an
    // element with the other language's (or Java's) own message (design.md D20, "Foreign access in
    // compiled code"). The interop is the runtime's (AGENTS.md rule 1).

    /** {@code (foreign? obj)}: no interop, only whether it is a Scheme value. */
    static final class IsForeign extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return Foreign.isForeign(args[1]);
        }
    }

    static final class IsNull extends PrimitiveNode {
        @Child private ForeignNodes.IsNullNode node = ForeignNodes.IsNullNode.create();

        @Override
        public Object invoke(Object[] args) {
            return node.execute(args[1]);
        }
    }

    static final class Ref extends PrimitiveNode {
        @Child private ForeignNodes.ReadNode node = ForeignNodes.ReadNode.create();

        @Override
        public Object invoke(Object[] args) {
            return node.execute(args[1], args[2]);
        }
    }

    static final class Set extends PrimitiveNode {
        @Child private ForeignNodes.WriteNode node = ForeignNodes.WriteNode.create();

        @Override
        public Object invoke(Object[] args) {
            node.execute(args[1], args[2], args[3]);
            return SConstant.UNSPECIFIED;
        }
    }

    static final class Length extends PrimitiveNode {
        @Child private ForeignNodes.LengthNode node = ForeignNodes.LengthNode.create();

        @Override
        public Object invoke(Object[] args) {
            return node.execute(args[1]);
        }
    }

    /** {@code foreign-keys} (a list), {@code foreign->list} and {@code foreign->vector}. */
    static final class Elements extends PrimitiveNode {
        private final Primitive op;
        private final String who;
        @Child private ForeignNodes.ElementsNode node = ForeignNodes.ElementsNode.create();

        Elements(Primitive op) {
            this.op = op;
            this.who = op.publicName();
        }

        @Override
        public Object invoke(Object[] args) {
            Object[] items = node.execute(who, op == Primitive.KEYS, args[1]);
            return op == Primitive.TO_VECTOR ? new SVector(items, false) : list(items);
        }

        private static Object list(Object[] items) {
            Object list = SConstant.EMPTY_LIST;
            for (int i = items.length - 1; i >= 0; i--) {
                list = new SPair(items[i], list);
            }
            return list;
        }
    }

    static final class Invoke extends PrimitiveNode {
        @Child private ForeignNodes.InvokeNode node = ForeignNodes.InvokeNode.create();

        @Override
        public Object invoke(Object[] args) {
            return node.execute(args[1], args[2], args, 3);
        }
    }

    static final class New extends PrimitiveNode {
        @Child private ForeignNodes.InstantiateNode node = ForeignNodes.InstantiateNode.create();

        @Override
        public Object invoke(Object[] args) {
            return node.execute(args[1], args, 2);
        }
    }

    static final class IsInstance extends PrimitiveNode {
        @Child private ForeignNodes.IsInstanceNode node = ForeignNodes.IsInstanceNode.create();

        @Override
        public Object invoke(Object[] args) {
            return node.execute(args[1], args[2]);
        }
    }
}
