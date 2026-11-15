package scheme.builtins;

import java.util.List;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.runtime.Capture;
import scheme.runtime.Continuation;
import scheme.runtime.DynEnv;
import scheme.runtime.InvokeContinuation;
import scheme.runtime.PrimitiveNode;
import scheme.runtime.PrimitiveSpec;
import scheme.runtime.SchemeContext;
import scheme.runtime.SchemeError;
import scheme.runtime.TailCall;
import scheme.runtime.printer.Printer;
import scheme.runtime.value.MultipleValues;
import scheme.runtime.value.SBigInteger;
import scheme.runtime.value.SChar;
import scheme.runtime.value.SComplex;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SErrorObject;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SRational;
import scheme.runtime.value.SRecord;
import scheme.runtime.value.SRecordType;
import scheme.runtime.value.SString;
import scheme.runtime.value.SSymbol;
import scheme.runtime.value.SVector;

/**
 * The control features and exceptions area (R7RS §6.1, §6.10, §6.11, and records §5.5): its primitives, all leaves (AGENTS.md rule 1). One of the
 * fan-out areas (AGENTS.md "Areas"): add this area's primitives here and nowhere else.
 */
final class ControlPrimitives {

    private ControlPrimitives() {
    }

    /** This area's primitives: the one list to add to. */
    static List<PrimitiveSpec> primitives() {
        return List.of(
                        new PrimitiveSpec("eq?", 2, 2, Eq::new),
                        new PrimitiveSpec("eqv?", 2, 2, Eqv::new),
                        new PrimitiveSpec("not", 1, 1, () -> new TypePredicate(x -> x instanceof Boolean b && !b)),
                        new PrimitiveSpec("values", 0, -1, Values::new),
                        new PrimitiveSpec("%values->list", 1, 1, ValuesToList::new),
                        new PrimitiveSpec("apply", 1, -1, Apply::new),
                        new PrimitiveSpec("call/cc", 1, 1, CallCC::new),
                        new PrimitiveSpec("call-with-current-continuation", 1, 1, CallCC::new),
                        new PrimitiveSpec("%capture-to-prompt", 2, 2, CaptureToPrompt::new),
                        new PrimitiveSpec("%torture-captures", 0, 0, TortureCaptures::new),
                        new PrimitiveSpec("%apply-continuation", 2, 2, () -> new ContinuationOps(ContinuationOps.APPLY)),
                        new PrimitiveSpec("%reinstate", 2, 2, () -> new ContinuationOps(ContinuationOps.REINSTATE)),
                        new PrimitiveSpec("%continuation-composable?", 1, 1, () -> new ContinuationOps(ContinuationOps.COMPOSABLE)),
                        new PrimitiveSpec("%continuation-dynamic-env", 1, 1, () -> new ContinuationOps(ContinuationOps.DYNAMIC_ENV)),
                        new PrimitiveSpec("%error", 1, -1, ErrorPrimitive::new),
                        new PrimitiveSpec("%set-receive!", 1, 1, SetReceive::new),
                        new PrimitiveSpec("%current-dynamic-env", 0, 0, () -> new DynamicEnv(DynamicEnv.CURRENT)),
                        new PrimitiveSpec("%set-dynamic-env!", 1, 1, () -> new DynamicEnv(DynamicEnv.SET)),
                        new PrimitiveSpec("%make-dynamic-env", 3, 3, () -> new DynamicEnv(DynamicEnv.MAKE)),
                        new PrimitiveSpec("%dynamic-env?", 1, 1, () -> new DynamicEnv(DynamicEnv.TEST)),
                        new PrimitiveSpec("%dynamic-env-winders", 1, 1, () -> new DynamicEnv(DynamicEnv.WINDERS)),
                        new PrimitiveSpec("%dynamic-env-parameterization", 1, 1, () -> new DynamicEnv(DynamicEnv.PARAMETERIZATION)),
                        new PrimitiveSpec("%dynamic-env-handlers", 1, 1, () -> new DynamicEnv(DynamicEnv.HANDLERS)),
                        new PrimitiveSpec("%set-raise!", 1, 1, SetRaise::new),
                        new PrimitiveSpec("%raise-uncaught", 1, 1, RaiseUncaught::new),
                        new PrimitiveSpec("%make-error-object", 2, 2, MakeErrorObject::new),
                        new PrimitiveSpec("error-object?", 1, 1, () -> new ErrorObjectOp(ErrorObjectOp.TEST)),
                        new PrimitiveSpec("error-object-message", 1, 1, () -> new ErrorObjectOp(ErrorObjectOp.MESSAGE)),
                        new PrimitiveSpec("error-object-irritants", 1, 1, () -> new ErrorObjectOp(ErrorObjectOp.IRRITANTS)),
                        new PrimitiveSpec("read-error?", 1, 1, () -> new ErrorObjectOp(ErrorObjectOp.READ)),
                        new PrimitiveSpec("file-error?", 1, 1, () -> new ErrorObjectOp(ErrorObjectOp.FILE)),
                        new PrimitiveSpec("%make-record-type", 2, 2, MakeRecordType::new),
                        new PrimitiveSpec("%record-index", 2, 2, RecordIndex::new),
                        new PrimitiveSpec("%make-record", 2, -1, MakeRecord::new),
                        new PrimitiveSpec("%record?", 2, 2, RecordTest::new),
                        new PrimitiveSpec("%record-ref", 3, 3, () -> new RecordAccess(false)),
                        new PrimitiveSpec("%record-set!", 4, 4, () -> new RecordAccess(true)));
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

    /** {@code (eqv? a b)} (R7RS §6.1): eq?, or numbers of the same exactness and value, or equal characters. */
    static final class Eqv extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return eqv(args[0], args[1]);
        }

        @TruffleBoundary
        static boolean eqv(Object a, Object b) {
            if (a == b) {
                return true;
            }
            if (a instanceof Long || a instanceof Double || a instanceof Boolean) {
                return a.equals(b);
            }
            if (a instanceof SChar x) {
                return b instanceof SChar y && x.codePoint == y.codePoint;
            }
            if (a instanceof SBigInteger x) {
                return b instanceof SBigInteger y && x.value.equals(y.value);
            }
            if (a instanceof SRational x) {
                return b instanceof SRational y && x.numerator.equals(y.numerator) && x.denominator.equals(y.denominator);
            }
            if (a instanceof SComplex x) {
                return b instanceof SComplex y && eqv(x.real, y.real) && eqv(x.imag, y.imag);
            }
            return false;
        }
    }

    /** {@code (values obj ...)}: one value is itself, otherwise MultipleValues (design.md D1). */
    static final class Values extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return args.length == 1 ? args[0] : new MultipleValues(args.clone());
        }
    }

    /** {@code (%values->list v)}: the values a producer returned, as a list (for call-with-values). */
    static final class ValuesToList extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            Object[] values = args[0] instanceof MultipleValues m ? m.values : new Object[]{args[0]};
            Object result = SConstant.EMPTY_LIST;
            for (int i = values.length - 1; i >= 0; i--) {
                result = new SPair(values[i], result);
            }
            return result;
        }
    }

    /**
     * {@code (apply proc arg ... list)}: returns a tail call to proc (rule 1, design.md D5), which
     * the call site's trampoline performs in constant space.
     */
    static final class Apply extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return tailCall(args);
        }

        @TruffleBoundary
        private static TailCall tailCall(Object[] args) {
            if (args.length == 1) {
                return new TailCall(args[0], new Object[]{args[0]});
            }
            Object[] spread = Lists.toArray("apply", args[args.length - 1]);
            Object[] all = new Object[args.length - 1 + spread.length];
            all[0] = args[0];
            System.arraycopy(args, 1, all, 1, args.length - 2);
            System.arraycopy(spread, 0, all, args.length - 1, spread.length);
            return new TailCall(args[0], all);
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

    /** Installs the prelude's %receive (control.scm), which the prompt driver calls. */
    static final class SetReceive extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            SchemeContext.get(this).receive = args[0];
            return SConstant.UNSPECIFIED;
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

    /** Installs the prelude's raise, which call sites call with primitive errors (D8). */
    static final class SetRaise extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            SchemeContext.get(this).raise = args[0];
            return SConstant.UNSPECIFIED;
        }
    }

    /** {@code (%raise-uncaught obj)}: raise found no handler, so the program ends (D8). */
    static final class RaiseUncaught extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            throw uncaught(args[0]);
        }

        @TruffleBoundary
        private static SchemeError uncaught(Object raised) {
            String message;
            if (raised instanceof SErrorObject e) {
                StringBuilder text = new StringBuilder(Printer.print(e.message, Printer.Mode.DISPLAY));
                for (Object l = e.irritants; l instanceof SPair p; l = p.cdr) {
                    text.append(' ').append(Printer.print(p.car, Printer.Mode.WRITE));
                }
                message = text.toString();
            } else {
                message = "uncaught exception: " + Printer.print(raised, Printer.Mode.WRITE);
            }
            return SchemeError.uncaught(message, raised);
        }
    }

    /** {@code (%make-error-object message irritants)}: what {@code error} raises. */
    static final class MakeErrorObject extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return new SErrorObject(args[0], args[1], SErrorObject.Kind.OTHER);
        }
    }

    static final class ErrorObjectOp extends PrimitiveNode {
        static final int TEST = 0;
        static final int MESSAGE = 1;
        static final int IRRITANTS = 2;
        static final int READ = 3;
        static final int FILE = 4;

        private final int op;

        ErrorObjectOp(int op) {
            this.op = op;
        }

        @Override
        public Object execute(Object[] args) {
            Object x = args[0];
            switch (op) {
                case TEST:
                    return x instanceof SErrorObject;
                case READ:
                    return x instanceof SErrorObject e && e.kind == SErrorObject.Kind.READ;
                case FILE:
                    return x instanceof SErrorObject e && e.kind == SErrorObject.Kind.FILE;
                default:
                    if (!(x instanceof SErrorObject e)) {
                        throw notA(op == MESSAGE ? "error-object-message" : "error-object-irritants", "error object", x);
                    }
                    return op == MESSAGE ? e.message : e.irritants;
            }
        }
    }

    /** {@code (%make-record-type 'name '(field ...))}. */
    static final class MakeRecordType extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return make(args[0], args[1]);
        }

        @TruffleBoundary
        private static SRecordType make(Object name, Object fields) {
            Object[] names = Lists.toArray("define-record-type", fields);
            String[] strings = new String[names.length];
            for (int i = 0; i < names.length; i++) {
                strings[i] = names[i].toString();
            }
            return new SRecordType(Printer.print(name, Printer.Mode.DISPLAY), strings);
        }
    }

    /** {@code (%record-index type 'field)}: the field's index, at definition time. */
    static final class RecordIndex extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return index((SRecordType) args[0], args[1]);
        }

        @TruffleBoundary
        private static long index(SRecordType type, Object field) {
            String name = field instanceof SSymbol ? field.toString() : "";
            for (int i = 0; i < type.fieldNames.length; i++) {
                if (type.fieldNames[i].equals(name)) {
                    return i;
                }
            }
            throw new SchemeError("define-record-type: " + Printer.print(field, Printer.Mode.WRITE) + " is not a field of " + type.name);
        }
    }

    /** {@code (%make-record type #(index ...) value ...)}: fields not given are unspecified. */
    static final class MakeRecord extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            SRecordType type = (SRecordType) args[0];
            Object[] indices = ((SVector) args[1]).elements;
            Object[] fields = new Object[type.fieldNames.length];
            java.util.Arrays.fill(fields, SConstant.UNSPECIFIED);
            for (int i = 0; i < indices.length; i++) {
                fields[(int) (long) (Long) indices[i]] = args[i + 2];
            }
            return new SRecord(type, fields);
        }
    }

    /** {@code (%record? type x)}. */
    static final class RecordTest extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return args[1] instanceof SRecord r && r.type == args[0];
        }
    }

    /** {@code (%record-ref type index r)} and {@code (%record-set! type index r value)}. */
    static final class RecordAccess extends PrimitiveNode {
        private final boolean set;

        RecordAccess(boolean set) {
            this.set = set;
        }

        @Override
        public Object execute(Object[] args) {
            SRecordType type = (SRecordType) args[0];
            int index = (int) (long) (Long) args[1];
            if (!(args[2] instanceof SRecord r) || r.type != type) {
                throw notA(type.fieldNames[index], "record of type " + type.name, args[2]);
            }
            if (set) {
                r.fields[index] = args[3];
                return SConstant.UNSPECIFIED;
            }
            return r.fields[index];
        }
    }

    @TruffleBoundary
    static SchemeError notA(String who, String what, Object value) {
        return new SchemeError(who + ": not a " + what + ": " + Printer.print(value, Printer.Mode.WRITE));
    }
}
