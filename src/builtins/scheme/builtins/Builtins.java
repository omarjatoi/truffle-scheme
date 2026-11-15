package scheme.builtins;

import java.util.List;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.runtime.BuiltinLibrary;
import scheme.runtime.Capture;
import scheme.runtime.Continuation;
import scheme.runtime.InvokeContinuation;
import scheme.runtime.DynEnv;
import scheme.runtime.PrimitiveNode;
import scheme.runtime.PrimitiveSpec;
import scheme.runtime.SchemeContext;
import scheme.runtime.SchemeError;
import scheme.runtime.printer.Printer;
import scheme.runtime.value.SChar;
import scheme.runtime.value.MultipleValues;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SErrorObject;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SVector;
import scheme.runtime.value.SString;

/**
 * The primitives M1 needs (arithmetic, comparison, pairs, output). Every one is a leaf: none calls
 * Scheme (AGENTS.md rule 1). The full set, and the {@code (r7rs %primitives)} library (design.md
 * D7), come with M3 and M4.
 */
public final class Builtins implements BuiltinLibrary {

    @Override
    public List<PrimitiveSpec> primitives() {
        return List.of(
                        new PrimitiveSpec("+", 0, -1, Add::new),
                        new PrimitiveSpec("-", 1, -1, Sub::new),
                        new PrimitiveSpec("*", 0, -1, Mul::new),
                        new PrimitiveSpec("=", 1, -1, () -> new Compare("=", Compare.EQ)),
                        new PrimitiveSpec("<", 1, -1, () -> new Compare("<", Compare.LT)),
                        new PrimitiveSpec(">", 1, -1, () -> new Compare(">", Compare.GT)),
                        new PrimitiveSpec("<=", 1, -1, () -> new Compare("<=", Compare.LE)),
                        new PrimitiveSpec(">=", 1, -1, () -> new Compare(">=", Compare.GE)),
                        new PrimitiveSpec("cons", 2, 2, Cons::new),
                        new PrimitiveSpec("vector", 0, -1, MakeVector::new),
                        new PrimitiveSpec("car", 1, 1, () -> new PairAccess("car", true)),
                        new PrimitiveSpec("cdr", 1, 1, () -> new PairAccess("cdr", false)),
                        new PrimitiveSpec("null?", 1, 1, () -> new TypeTest(TypeTest.NULL)),
                        new PrimitiveSpec("pair?", 1, 1, () -> new TypeTest(TypeTest.PAIR)),
                        new PrimitiveSpec("not", 1, 1, () -> new TypeTest(TypeTest.FALSE)),
                        new PrimitiveSpec("eq?", 2, 2, Eq::new),
                        new PrimitiveSpec("display", 1, 1, () -> new Output(Printer.Mode.DISPLAY)),
                        new PrimitiveSpec("write", 1, 1, () -> new Output(Printer.Mode.WRITE)),
                        new PrimitiveSpec("newline", 0, 0, () -> new Output(null)),
                        // Internal primitives for the prelude (design.md D5).
                        new PrimitiveSpec("call/cc", 1, 1, CallCC::new),
                        new PrimitiveSpec("call-with-current-continuation", 1, 1, CallCC::new),
                        new PrimitiveSpec("%capture-to-prompt", 2, 2, CaptureToPrompt::new),
                        new PrimitiveSpec("%torture-captures", 0, 0, TortureCaptures::new),
                        new PrimitiveSpec("%apply-continuation", 2, 2, () -> new ContinuationOps(ContinuationOps.APPLY)),
                        new PrimitiveSpec("%reinstate", 2, 2, () -> new ContinuationOps(ContinuationOps.REINSTATE)),
                        new PrimitiveSpec("%continuation-composable?", 1, 1, () -> new ContinuationOps(ContinuationOps.COMPOSABLE)),
                        new PrimitiveSpec("%continuation-dynamic-env", 1, 1, () -> new ContinuationOps(ContinuationOps.DYNAMIC_ENV)),
                        new PrimitiveSpec("%error", 1, -1, ErrorPrimitive::new),
                        new PrimitiveSpec("%current-dynamic-env", 0, 0, () -> new DynamicEnv(DynamicEnv.CURRENT)),
                        new PrimitiveSpec("%set-dynamic-env!", 1, 1, () -> new DynamicEnv(DynamicEnv.SET)),
                        new PrimitiveSpec("%make-dynamic-env", 3, 3, () -> new DynamicEnv(DynamicEnv.MAKE)),
                        new PrimitiveSpec("%dynamic-env?", 1, 1, () -> new DynamicEnv(DynamicEnv.TEST)),
                        new PrimitiveSpec("%dynamic-env-winders", 1, 1, () -> new DynamicEnv(DynamicEnv.WINDERS)),
                        new PrimitiveSpec("%dynamic-env-parameterization", 1, 1, () -> new DynamicEnv(DynamicEnv.PARAMETERIZATION)),
                        new PrimitiveSpec("%dynamic-env-handlers", 1, 1, () -> new DynamicEnv(DynamicEnv.HANDLERS)));
    }

    static final class Add extends PrimitiveNode {
        @Child private ArithmeticNodes.AddNode add = ArithmeticNodesFactory.AddNodeGen.create();

        @Override
        public Object execute(Object[] args) {
            if (args.length == 0) {
                return 0L;
            }
            Object acc = Arithmetic.check("+", args[0]);
            for (int i = 1; i < args.length; i++) {
                acc = add.execute(acc, args[i]);
            }
            return acc;
        }
    }

    static final class Sub extends PrimitiveNode {
        @Child private ArithmeticNodes.SubNode sub = ArithmeticNodesFactory.SubNodeGen.create();

        @Override
        public Object execute(Object[] args) {
            if (args.length == 1) {
                return sub.execute(0L, args[0]);
            }
            Object acc = Arithmetic.check("-", args[0]);
            for (int i = 1; i < args.length; i++) {
                acc = sub.execute(acc, args[i]);
            }
            return acc;
        }
    }

    static final class Mul extends PrimitiveNode {
        @Child private ArithmeticNodes.MulNode mul = ArithmeticNodesFactory.MulNodeGen.create();

        @Override
        public Object execute(Object[] args) {
            if (args.length == 0) {
                return 1L;
            }
            Object acc = Arithmetic.check("*", args[0]);
            for (int i = 1; i < args.length; i++) {
                acc = mul.execute(acc, args[i]);
            }
            return acc;
        }
    }

    static final class Compare extends PrimitiveNode {
        static final int EQ = 0;
        static final int LT = 1;
        static final int GT = 2;
        static final int LE = 3;
        static final int GE = 4;

        private final String name;
        private final int kind;
        @Child private ArithmeticNodes.CompareNode compare = ArithmeticNodesFactory.CompareNodeGen.create();

        Compare(String name, int kind) {
            this.name = name;
            this.kind = kind;
        }

        @Override
        public Object execute(Object[] args) {
            Arithmetic.check(name, args[0]);
            boolean result = true;
            for (int i = 1; i < args.length; i++) {
                int c = compare.execute(args[i - 1], args[i]);
                boolean ok = switch (kind) {
                    case EQ -> c == 0;
                    case LT -> c < 0;
                    case GT -> c > 0;
                    case LE -> c <= 0;
                    default -> c >= 0;
                };
                result &= ok;
            }
            return result;
        }
    }

    static final class Cons extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return new SPair(args[0], args[1]);
        }
    }

    static final class MakeVector extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return new SVector(args.clone(), false);
        }
    }

    static final class PairAccess extends PrimitiveNode {
        private final String name;
        private final boolean car;

        PairAccess(String name, boolean car) {
            this.name = name;
            this.car = car;
        }

        @Override
        public Object execute(Object[] args) {
            if (args[0] instanceof SPair p) {
                return car ? p.car : p.cdr;
            }
            throw notAPair(name, args[0]);
        }

        @TruffleBoundary
        private static SchemeError notAPair(String name, Object value) {
            return new SchemeError(name + ": not a pair: " + Printer.print(value, Printer.Mode.WRITE));
        }
    }

    static final class TypeTest extends PrimitiveNode {
        static final int NULL = 0;
        static final int PAIR = 1;
        static final int FALSE = 2;

        private final int kind;

        TypeTest(int kind) {
            this.kind = kind;
        }

        @Override
        public Object execute(Object[] args) {
            Object x = args[0];
            return switch (kind) {
                case NULL -> x == SConstant.EMPTY_LIST;
                case PAIR -> x instanceof SPair;
                default -> x instanceof Boolean b && !b;
            };
        }
    }

    /** eq?: identity, except that fixnums and characters compare by value (design.md D1). */
    static final class Eq extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            Object a = args[0];
            Object b = args[1];
            if (a == b) {
                return true;
            }
            if (a instanceof Long x && b instanceof Long y) {
                return x.longValue() == y.longValue();
            }
            if (a instanceof SChar x && b instanceof SChar y) {
                return x.codePoint == y.codePoint;
            }
            if (a instanceof Boolean x && b instanceof Boolean y) {
                return x.booleanValue() == y.booleanValue();
            }
            return false;
        }
    }

    /** display, write and newline on the current output port (ports are M4). */
    static final class Output extends PrimitiveNode {
        /** null for newline. */
        private final Printer.Mode mode;

        Output(Printer.Mode mode) {
            this.mode = mode;
        }

        @Override
        public Object execute(Object[] args) {
            print(SchemeContext.get(this), mode, mode == null ? null : args[0]);
            return SConstant.UNSPECIFIED;
        }

        @TruffleBoundary
        private static void print(SchemeContext context, Printer.Mode mode, Object value) {
            if (mode == null) {
                context.out().print('\n');
            } else {
                context.out().print(Printer.print(value, mode));
            }
            context.out().flush();
        }
    }

    /**
     * {@code (%error message irritant ...)}: signals an error with an error object (design.md D8).
     * The prelude uses it until {@code raise} and {@code error} exist (M3, M4).
     */
    static final class ErrorPrimitive extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            throw error(args);
        }

        @TruffleBoundary
        private static SchemeError error(Object[] args) {
            Object message = args[0] instanceof SString ? args[0] : SString.of(Printer.print(args[0], Printer.Mode.DISPLAY));
            Object irritants = SConstant.EMPTY_LIST;
            for (int i = args.length - 1; i >= 1; i--) {
                irritants = new SPair(args[i], irritants);
            }
            StringBuilder text = new StringBuilder(message.toString());
            for (int i = 1; i < args.length; i++) {
                text.append(' ').append(Printer.print(args[i], Printer.Mode.WRITE));
            }
            return new SchemeError(new SErrorObject(SString.of(text.toString()), irritants, SErrorObject.Kind.OTHER));
        }
    }

    /** The dynamic environment record and the context's current one (design.md "Dynamic environment"). */
    static final class DynamicEnv extends PrimitiveNode {
        static final int CURRENT = 0;
        static final int SET = 1;
        static final int MAKE = 2;
        static final int TEST = 3;
        static final int WINDERS = 4;
        static final int PARAMETERIZATION = 5;
        static final int HANDLERS = 6;

        private final int kind;

        DynamicEnv(int kind) {
            this.kind = kind;
        }

        @Override
        public Object execute(Object[] args) {
            switch (kind) {
                case CURRENT:
                    return SchemeContext.get(this).getDynamicEnv();
                case SET:
                    SchemeContext.get(this).setDynamicEnv(dynEnv(args[0]));
                    return SConstant.UNSPECIFIED;
                case MAKE:
                    return new DynEnv(args[0], args[1], args[2]);
                case TEST:
                    return args[0] instanceof DynEnv;
                case WINDERS:
                    return dynEnv(args[0]).winders;
                case PARAMETERIZATION:
                    return dynEnv(args[0]).parameterization;
                default:
                    return dynEnv(args[0]).handlers;
            }
        }

        private static DynEnv dynEnv(Object value) {
            if (value instanceof DynEnv e) {
                return e;
            }
            throw notDynEnv(value);
        }

        @TruffleBoundary
        private static SchemeError notDynEnv(Object value) {
            return new SchemeError("not a dynamic environment: " + Printer.print(value, Printer.Mode.WRITE));
        }
    }

    /**
     * {@code (call/cc receiver)}: starts a capture (design.md "Continuations"). It doesn't call the
     * receiver itself (rule 1): it returns a Capture, every call site on the way out suspends its
     * frame, and the prompt calls the receiver with the completed continuation.
     */
    static final class CallCC extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return Capture.full(args[0], SchemeContext.get(this).getDynamicEnv());
        }
    }

    /**
     * {@code (%capture-to-prompt tag receiver)}: captures up to the nearest prompt with tag, aborts to
     * it, and calls receiver there with the composable continuation (design.md D4). Like call/cc, it
     * only returns a Capture (rule 1).
     */
    static final class CaptureToPrompt extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return Capture.delimited(args[0], args[1], SchemeContext.get(this).getDynamicEnv());
        }
    }

    /**
     * Continuation transfers for the prelude's %continue (control.scm). None calls Scheme (rule 1):
     * {@code %apply-continuation} throws to the root prompt, {@code %reinstate} returns a request that
     * travels to the nearest prompt.
     */
    static final class ContinuationOps extends PrimitiveNode {
        static final int APPLY = 0;
        static final int REINSTATE = 1;
        static final int COMPOSABLE = 2;
        static final int DYNAMIC_ENV = 3;

        private final int kind;

        ContinuationOps(int kind) {
            this.kind = kind;
        }

        @Override
        public Object execute(Object[] args) {
            Continuation k = continuation(args[0]);
            switch (kind) {
                case APPLY:
                    throw new InvokeContinuation(k, values(args[1]));
                case REINSTATE:
                    return Capture.reinstate(k, values(args[1]), SchemeContext.get(this).getDynamicEnv());
                case COMPOSABLE:
                    return k.composable;
                default:
                    return k.dynEnv;
            }
        }

        private static Continuation continuation(Object value) {
            if (value instanceof Continuation k) {
                return k;
            }
            throw notAContinuation(value);
        }

        /** A list of values: one value is itself, otherwise MultipleValues (design.md D1). */
        @TruffleBoundary
        private static Object values(Object list) {
            java.util.ArrayList<Object> items = new java.util.ArrayList<>();
            for (Object l = list; l instanceof SPair p; l = p.cdr) {
                items.add(p.car);
            }
            return items.size() == 1 ? items.get(0) : new MultipleValues(items.toArray());
        }

        @TruffleBoundary
        private static SchemeError notAContinuation(Object value) {
            return new SchemeError("not a continuation: " + Printer.print(value, Printer.Mode.WRITE));
        }
    }

    /** {@code (%torture-captures)}: how many torture-mode captures this context has completed (M2-06 tests). */
    static final class TortureCaptures extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return SchemeContext.get(this).tortureCaptures;
        }
    }
}
