package scheme.builtins;

import scheme.runtime.value.SBytevector;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.staticobject.StaticProperty;

import scheme.runtime.Capture;
import scheme.runtime.Continuation;
import scheme.runtime.DynEnv;
import scheme.runtime.Foreign;
import scheme.runtime.Intrinsics;
import scheme.runtime.PrimitiveNode;
import scheme.runtime.PrimitiveRootNode;
import scheme.runtime.PrimitiveSpec;
import scheme.runtime.PrimitiveSpec.Result;
import scheme.runtime.SchemeContext;
import scheme.runtime.SchemeError;
import scheme.runtime.SchemeLanguage;
import scheme.runtime.TailCall;
import scheme.runtime.printer.Printer;
import scheme.runtime.value.MultipleValues;
import scheme.runtime.value.SBigInteger;
import scheme.runtime.value.SChar;
import scheme.runtime.value.SClosure;
import scheme.runtime.value.SComplex;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SErrorObject;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SPromise;
import scheme.runtime.value.SRational;
import scheme.runtime.staticobject.SRecord;
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

    /** This area's primitives, as {@code Builtins} registers them. */
    static List<PrimitiveSpec> primitives() {
        List<PrimitiveSpec> specs = new ArrayList<>();
        for (Primitive primitive : Primitive.values()) {
            specs.add(primitive.spec);
        }
        return specs;
    }

    /**
     * This area's primitives: the one list to add to. A constant has the primitive's name and
     * arity, and {@link #node} makes its node when it is first called: no lambda per primitive for
     * the JVM to link as a context starts (design.md D17, "The primitives' registry").
     */
    enum Primitive implements Supplier<PrimitiveNode> {
        EQ("eq?", 2, 2),
        EQV("eqv?", 2, 2),
        EQUAL("equal?", 2, 2),
        NOT("not", 1, 1),
        IS_BOOLEAN("boolean?", 1, 1),
        BOOLEAN_EQ("boolean=?", 2, -1),
        IS_PROCEDURE("procedure?", 1, 1),
        VALUES("values", 0, -1),
        VALUES_TO_LIST("%values->list", 1, 1),
        APPLY("apply", 2, -1, Result.TRANSFERS_CONTROL),
        CALL_CC("call/cc", 1, 1, Result.TRANSFERS_CONTROL),
        CALL_WITH_CURRENT_CONTINUATION("call-with-current-continuation", 1, 1, Result.TRANSFERS_CONTROL),
        CAPTURE_TO_PROMPT("%capture-to-prompt", 2, 2, Result.TRANSFERS_CONTROL),
        TORTURE_CAPTURES("%torture-captures", 0, 0),
        SPILLS("%spills", 0, 0),
        IN_PLACE_RESUMES("%in-place-resumes", 0, 0),
        COPIED_RESUMES("%copied-resumes", 0, 0),
        STACK_PROBES("%stack-probes", 0, 0),
        APPLY_CONTINUATION("%apply-continuation", 2, 2, Result.TRANSFERS_CONTROL),
        REINSTATE("%reinstate", 2, 2, Result.TRANSFERS_CONTROL),
        IS_CONTINUATION_COMPOSABLE("%continuation-composable?", 1, 1),
        CONTINUATION_DYNAMIC_ENV("%continuation-dynamic-env", 1, 1),
        ERROR("%error", 1, -1),
        SET_RECEIVE("%set-receive!", 1, 1),
        SET_ESCAPE("%set-escape!", 1, 1),
        SET_UNWIND_ROOT("%set-unwind-root!", 1, 1),
        CURRENT_DYNAMIC_ENV("%current-dynamic-env", 0, 0),
        SET_DYNAMIC_ENV("%set-dynamic-env!", 1, 1),
        MAKE_DYNAMIC_ENV("%make-dynamic-env", 3, 3),
        IS_DYNAMIC_ENV("%dynamic-env?", 1, 1),
        DYNAMIC_ENV_WINDERS("%dynamic-env-winders", 1, 1),
        DYNAMIC_ENV_PARAMETERIZATION("%dynamic-env-parameterization", 1, 1),
        DYNAMIC_ENV_HANDLERS("%dynamic-env-handlers", 1, 1),
        SET_RAISE("%set-raise!", 1, 1),
        RAISE_UNCAUGHT("%raise-uncaught", 1, 1),
        MAKE_ERROR_OBJECT("%make-error-object", 2, 2, Result.NEW_OBJECT),
        IS_ERROR_OBJECT("error-object?", 1, 1),
        ERROR_OBJECT_MESSAGE("error-object-message", 1, 1),
        ERROR_OBJECT_IRRITANTS("error-object-irritants", 1, 1),
        IS_READ_ERROR("read-error?", 1, 1),
        IS_FILE_ERROR("file-error?", 1, 1),
        MAKE_PROMISE("%make-promise", 2, 2, Result.NEW_OBJECT),
        IS_PROMISE("promise?", 1, 1),
        IS_PROMISE_DONE("%promise-done?", 1, 1),
        PROMISE_VALUE("%promise-value", 1, 1),
        PROMISE_UPDATE("%promise-update!", 2, 2),
        MAKE_RECORD_TYPE("%make-record-type", 2, 2),
        RECORD_CONSTRUCTOR("%record-constructor", 3, 3),
        RECORD_PREDICATE("%record-predicate", 2, 2),
        RECORD_ACCESSOR("%record-accessor", 3, 3),
        RECORD_MODIFIER("%record-modifier", 3, 3);

        final PrimitiveSpec spec;

        Primitive(String name, int minArgs, int maxArgs) {
            this(name, minArgs, maxArgs, Result.VALUE);
        }

        Primitive(String name, int minArgs, int maxArgs, Result result) {
            this.spec = new PrimitiveSpec(name, minArgs, maxArgs, this, result);
        }

        @Override
        public PrimitiveNode get() {
            return (PrimitiveNode) node();
        }

        /**
         * The primitive's node: a {@code return} in each case, typed {@code Object}, so that the
         * JVM's verifier needn't load every node class to check what is returned (a switch
         * expression's cases would join as {@code PrimitiveNode}). Each is loaded when it is made.
         */
        private Object node() {
            switch (this) {
                case EQ:
                    return new Eq();
                case EQV:
                    return new Eqv();
                case EQUAL:
                    return new Equal();
                case NOT:
                    return new TypePredicate(x -> x instanceof Boolean b && !b);
                case IS_BOOLEAN:
                    return new TypePredicate(x -> x instanceof Boolean);
                case BOOLEAN_EQ:
                    return new BooleanEquals();
                case IS_PROCEDURE:
                    // A foreign executable is a procedure too (design.md D20).
                    return new TypePredicate(ControlPrimitives::isProcedure);
                case VALUES:
                    return new Values();
                case VALUES_TO_LIST:
                    return new ValuesToList();
                case APPLY:
                    return new Apply();
                case CALL_CC:
                    return new CallCC("call/cc");
                case CALL_WITH_CURRENT_CONTINUATION:
                    return new CallCC("call-with-current-continuation");
                case CAPTURE_TO_PROMPT:
                    return new CaptureToPrompt();
                case TORTURE_CAPTURES:
                    return new TortureCaptures();
                case SPILLS:
                    return new Spills();
                case IN_PLACE_RESUMES:
                    return new Resumes(true);
                case COPIED_RESUMES:
                    return new Resumes(false);
                case STACK_PROBES:
                    return new StackProbes();
                case APPLY_CONTINUATION:
                    return new ContinuationOps(ContinuationOps.APPLY);
                case REINSTATE:
                    return new ContinuationOps(ContinuationOps.REINSTATE);
                case IS_CONTINUATION_COMPOSABLE:
                    return new ContinuationOps(ContinuationOps.COMPOSABLE);
                case CONTINUATION_DYNAMIC_ENV:
                    return new ContinuationOps(ContinuationOps.DYNAMIC_ENV);
                case ERROR:
                    return new ErrorPrimitive();
                case SET_RECEIVE:
                    return new SetReceive();
                case SET_ESCAPE:
                    return new SetEscape();
                case SET_UNWIND_ROOT:
                    return new SetUnwindRoot();
                case CURRENT_DYNAMIC_ENV:
                    return new DynamicEnv(DynamicEnv.CURRENT);
                case SET_DYNAMIC_ENV:
                    return new DynamicEnv(DynamicEnv.SET);
                case MAKE_DYNAMIC_ENV:
                    return new DynamicEnv(DynamicEnv.MAKE);
                case IS_DYNAMIC_ENV:
                    return new DynamicEnv(DynamicEnv.TEST);
                case DYNAMIC_ENV_WINDERS:
                    return new DynamicEnv(DynamicEnv.WINDERS);
                case DYNAMIC_ENV_PARAMETERIZATION:
                    return new DynamicEnv(DynamicEnv.PARAMETERIZATION);
                case DYNAMIC_ENV_HANDLERS:
                    return new DynamicEnv(DynamicEnv.HANDLERS);
                case SET_RAISE:
                    return new SetRaise();
                case RAISE_UNCAUGHT:
                    return new RaiseUncaught();
                case MAKE_ERROR_OBJECT:
                    return new MakeErrorObject();
                case IS_ERROR_OBJECT:
                    return new ErrorObjectOp(ErrorObjectOp.TEST);
                case ERROR_OBJECT_MESSAGE:
                    return new ErrorObjectOp(ErrorObjectOp.MESSAGE);
                case ERROR_OBJECT_IRRITANTS:
                    return new ErrorObjectOp(ErrorObjectOp.IRRITANTS);
                case IS_READ_ERROR:
                    return new ErrorObjectOp(ErrorObjectOp.READ);
                case IS_FILE_ERROR:
                    return new ErrorObjectOp(ErrorObjectOp.FILE);
                case MAKE_PROMISE:
                    return new MakePromise();
                case IS_PROMISE:
                    return new TypePredicate(x -> x instanceof SPromise);
                case IS_PROMISE_DONE:
                    return new PromiseOp(PromiseOp.DONE);
                case PROMISE_VALUE:
                    return new PromiseOp(PromiseOp.VALUE);
                case PROMISE_UPDATE:
                    return new PromiseOp(PromiseOp.UPDATE);
                case MAKE_RECORD_TYPE:
                    return new MakeRecordType();
                case RECORD_CONSTRUCTOR:
                    return new RecordProcedure(RecordProcedure.CONSTRUCTOR);
                case RECORD_PREDICATE:
                    return new RecordProcedure(RecordProcedure.PREDICATE);
                case RECORD_ACCESSOR:
                    return new RecordProcedure(RecordProcedure.ACCESSOR);
                case RECORD_MODIFIER:
                    return new RecordProcedure(RecordProcedure.MODIFIER);
            }
            throw new AssertionError(this);
        }
    }

    /** eq?: identity, except that fixnums and characters compare by value (design.md D1). */
    static final class Eq extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return eq(args[1], args[2]);
        }

        /** The {@code Eq} operation's (plan.md M7-03), which compares numbers by value (design.md D1). */
        static boolean eq(Object a, Object b) {
            return Intrinsics.eq(a, b);
        }
    }

    /** {@code (boolean=? b1 b2 b3 ...)} (R7RS §6.3): all #t or all #f; every argument must be a boolean. */
    static final class BooleanEquals extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            boolean same = true;
            for (int i = 1; i < args.length; i++) {
                if (!(args[i] instanceof Boolean b)) {
                    throw notA("boolean=?", i, "boolean", args[i]);
                }
                same &= b.booleanValue() == (Boolean) args[1];
            }
            return same;
        }
    }

    /** {@code (eqv? a b)} (R7RS §6.1): eq?, or numbers of the same exactness and value, or equal characters. */
    static final class Eqv extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return eqv(args[1], args[2]);
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
        public Object invoke(Object[] args) {
            return args.length == 2 ? args[1] : new MultipleValues(java.util.Arrays.copyOfRange(args, 1, args.length));
        }
    }

    /** {@code (%values->list v)}: the values a producer returned, as a list (for call-with-values). */
    static final class ValuesToList extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            Object[] values = args[1] instanceof MultipleValues m ? m.values : new Object[]{args[1]};
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
        public Object invoke(Object[] args) {
            return tailCall(args);
        }

        @TruffleBoundary
        private static TailCall tailCall(Object[] args) {
            // args: apply, proc, arg ..., list; all: proc, arg ..., the list's elements.
            if (!isProcedure(args[1])) {
                throw notA("apply", 1, "procedure", args[1]);
            }
            Object[] spread = Lists.toArray("apply", args.length - 1, args[args.length - 1]);
            Object[] all = new Object[args.length - 2 + spread.length];
            all[0] = args[1];
            System.arraycopy(args, 2, all, 1, args.length - 3);
            System.arraycopy(spread, 0, all, args.length - 2, spread.length);
            return new TailCall(args[1], all);
        }
    }

    /**
     * {@code (call/cc receiver)}: starts a capture (design.md "Continuations"). It doesn't call the
     * receiver itself (rule 1): it returns a Capture, every call site on the way out suspends its
     * frame, and the prompt calls the receiver with the completed continuation.
     */
    static final class CallCC extends PrimitiveNode {
        private final String name;

        CallCC(String name) {
            this.name = name;
        }

        @Override
        public Object invoke(Object[] args) {
            if (!isProcedure(args[1])) {
                throw notA(name, 1, "procedure", args[1]);
            }
            return Capture.full(args[1], SchemeContext.get(this).getDynamicEnv());
        }
    }

    /** What {@code procedure?} is true of: a closure, or a foreign executable (design.md D20). */
    static boolean isProcedure(Object x) {
        return x instanceof SClosure || Foreign.isProcedure(x);
    }

    /**
     * {@code (%capture-to-prompt tag receiver)}: captures up to the nearest prompt with tag, aborts to
     * it, and calls receiver there with the composable continuation (design.md D4). Like call/cc, it
     * only returns a Capture (rule 1).
     */
    static final class CaptureToPrompt extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return Capture.delimited(args[1], args[2], SchemeContext.get(this).getDynamicEnv());
        }
    }

    /**
     * Continuation transfers for the prelude's %continue (control.scm). None calls Scheme (rule 1),
     * and none throws: each returns a request ({@link Capture}) that travels up the stack,
     * {@code %apply-continuation}'s to the root prompt, {@code %reinstate}'s to the nearest prompt.
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
        public Object invoke(Object[] args) {
            Continuation k = continuation(args[1]);
            switch (kind) {
                case APPLY:
                    return Capture.apply(k, values(args[2]), SchemeContext.get(this).getDynamicEnv());
                case REINSTATE:
                    return Capture.reinstate(k, values(args[2]), SchemeContext.get(this).getDynamicEnv());
                case COMPOSABLE:
                    return k.composable;
                default:
                    // %continue asks for it before it rewinds: so a continuation that can't be
                    // invoked here fails before any dynamic-wind thunk runs (design.md D20).
                    k.checkInvocable(SchemeContext.get(this));
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
            return notA("%continue", 1, "continuation", value);
        }
    }

    /** {@code (%torture-captures)}: how many torture-mode captures this context has completed (M2-06 tests). */
    static final class TortureCaptures extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return SchemeContext.get(this).tortureCaptures;
        }
    }

    /** {@code (%spills)}: how many stack-depth spills this context has made (M8-01 tests). */
    static final class Spills extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return SchemeContext.get(this).spills;
        }
    }

    /**
     * {@code (%in-place-resumes)}, {@code (%copied-resumes)}: how many segments this context's
     * prompt drivers have resumed in place (owned) and from a copy (shared), for tests (design.md
     * "Owned segments").
     */
    static final class Resumes extends PrimitiveNode {
        private final boolean inPlace;

        Resumes(boolean inPlace) {
            this.inPlace = inPlace;
        }

        @Override
        public Object invoke(Object[] args) {
            SchemeContext context = SchemeContext.get(this);
            return inPlace ? context.inPlaceResumes : context.copiedResumes;
        }
    }

    /**
     * {@code (%stack-probes)}: how many times this context has measured a thread's Java stack
     * (StackBudget), for tests: none on a thread whose size {@code scheme.StackSize} gives.
     */
    static final class StackProbes extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return SchemeContext.get(this).stackProbes;
        }
    }

    /**
     * {@code (%error message irritant ...)}: signals an error with an error object (design.md D8).
     * The prelude uses it until {@code raise} and {@code error} exist (M3, M4).
     */
    static final class ErrorPrimitive extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            throw error(args);
        }

        @TruffleBoundary
        private static SchemeError error(Object[] args) {
            // The message and the irritants, as error makes them: the message never repeats them
            // (design.md D8, "Messages").
            Object message = args[1] instanceof SString ? args[1] : SString.of(Printer.print(args[1], Printer.Mode.DISPLAY));
            Object irritants = SConstant.EMPTY_LIST;
            for (int i = args.length - 1; i >= 2; i--) {
                irritants = new SPair(args[i], irritants);
            }
            return new SchemeError(new SErrorObject(message, irritants, SErrorObject.Kind.OTHER));
        }
    }

    /** Installs the prelude's %receive (control.scm), which the prompt driver calls. */
    static final class SetReceive extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            SchemeContext.get(this).receive = args[1];
            return SConstant.UNSPECIFIED;
        }
    }

    /** Installs the prelude's %escape (control.scm), which escape-only continuations tail-call to rewind first (M5-04). */
    static final class SetEscape extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            SchemeContext.get(this).escape = args[1];
            return SConstant.UNSPECIFIED;
        }
    }

    /**
     * Installs the prelude's %unwind-root (control.scm), through which a root prompt runs the
     * after-thunks an error skips when it leaves the prompt (design.md D8, M7-06).
     */
    static final class SetUnwindRoot extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            SchemeContext.get(this).unwindRoot = args[1];
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
        public Object invoke(Object[] args) {
            switch (kind) {
                case CURRENT:
                    return SchemeContext.get(this).getDynamicEnv();
                case SET:
                    SchemeContext.get(this).setDynamicEnv(dynEnv(args[1]));
                    return SConstant.UNSPECIFIED;
                case MAKE:
                    return new DynEnv(args[1], args[2], args[3]);
                case TEST:
                    return args[1] instanceof DynEnv;
                case WINDERS:
                    return dynEnv(args[1]).winders;
                case PARAMETERIZATION:
                    return dynEnv(args[1]).parameterization;
                default:
                    return dynEnv(args[1]).handlers;
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
            return notA("%dynamic-env", 1, "dynamic environment", value);
        }
    }

    /** Installs the prelude's raise, which call sites call with primitive errors (D8). */
    static final class SetRaise extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            SchemeContext.get(this).raise = args[1];
            return SConstant.UNSPECIFIED;
        }
    }

    /** {@code (%raise-uncaught obj)}: raise found no handler, so the program ends (D8). */
    static final class RaiseUncaught extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            throw uncaught(args[1]);
        }

        @TruffleBoundary
        private static SchemeError uncaught(Object raised) {
            String message;
            if (raised instanceof SErrorObject e) {
                message = SchemeError.describe(e);
            } else {
                message = "uncaught exception: " + Printer.print(raised, Printer.Mode.WRITE);
            }
            return SchemeError.uncaught(message, raised);
        }
    }

    /** {@code (%make-error-object message irritants)}: what {@code error} raises. */
    static final class MakeErrorObject extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return new SErrorObject(args[1], args[2], SErrorObject.Kind.OTHER);
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
        public Object invoke(Object[] args) {
            Object x = args[1];
            switch (op) {
                case TEST:
                    // A foreign exception is an error object too (design.md D20).
                    return x instanceof SErrorObject || Foreign.isException(x);
                case READ:
                    return x instanceof SErrorObject e && e.kind == SErrorObject.Kind.READ;
                case FILE:
                    return x instanceof SErrorObject e && e.kind == SErrorObject.Kind.FILE;
                default:
                    if (!(x instanceof SErrorObject e)) {
                        if (Foreign.isException(x)) {
                            return op == MESSAGE ? Foreign.exceptionMessage(x) : SConstant.EMPTY_LIST;
                        }
                        throw notA(op == MESSAGE ? "error-object-message" : "error-object-irritants", 1, "error object", x);
                    }
                    return op == MESSAGE ? e.message : e.irritants;
            }
        }
    }

    /**
     * {@code (%make-promise done? obj)}: a new promise (R7RS §4.2.5, design.md D9), done with the
     * value {@code obj}, or not done with {@code obj} the thunk of a {@code delay-force}.
     */
    static final class MakePromise extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return new SPromise(!(args[1] instanceof Boolean b && !b), args[2]);
        }
    }

    /**
     * R7RS §7.3's promise operations, which {@code force} (lazy.scm) is made of:
     * {@code (%promise-done? p)}, {@code (%promise-value p)} (the value, or the thunk if not done),
     * and {@code (%promise-update! new p)}, which gives {@code p} the state of {@code new}, the
     * promise that {@code p}'s thunk returned, and makes {@code new} share {@code p}'s state.
     */
    static final class PromiseOp extends PrimitiveNode {
        static final int DONE = 0;
        static final int VALUE = 1;
        static final int UPDATE = 2;

        private final int op;

        PromiseOp(int op) {
            this.op = op;
        }

        @Override
        public Object invoke(Object[] args) {
            switch (op) {
                case DONE:
                    return promise(args[1]).state.done;
                case VALUE:
                    return promise(args[1]).state.value;
                default:
                    // The promise a delay-force's expression returned: anything else is an error
                    // (R7RS §4.2.5).
                    if (!(args[1] instanceof SPromise p)) {
                        throw notAPromise(args[1]);
                    }
                    p.updateInto(promise(args[2]));
                    return SConstant.UNSPECIFIED;
            }
        }

        private static SPromise promise(Object x) {
            if (x instanceof SPromise p) {
                return p;
            }
            throw notA("force", 1, "promise", x);
        }

        @TruffleBoundary
        private static SchemeError notAPromise(Object x) {
            return SchemeError.of("delay-force: its expression's value is not a promise", x);
        }
    }

    /** {@code (%make-record-type 'name '(field ...))}: a new type, whatever types came before. */
    static final class MakeRecordType extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return make(args[1], args[2]);
        }

        @TruffleBoundary
        private SRecordType make(Object name, Object fields) {
            Object[] names = Lists.toArray("define-record-type", 2, fields);
            String[] strings = new String[names.length];
            for (int i = 0; i < names.length; i++) {
                strings[i] = names[i].toString();
            }
            return new SRecordType(Printer.print(name, Printer.Mode.DISPLAY), strings, SchemeLanguage.get(this).recordShapes());
        }
    }

    /**
     * The procedures of a record type (plan.md M7-04): {@code (%record-constructor type '(field ...)
     * 'name)}, {@code (%record-predicate type 'name)}, {@code (%record-accessor type 'field 'name)}
     * and {@code (%record-modifier type 'field 'name)} each make a primitive procedure of its own,
     * named {@code name}, whose node has the type and the fields' properties as constants. So a call
     * site runs a copy of that node in place (M5-09), and an access compiles to a check of the
     * record's type and a field load. They run when the type is defined.
     */
    static final class RecordProcedure extends PrimitiveNode {
        static final int CONSTRUCTOR = 0;
        static final int PREDICATE = 1;
        static final int ACCESSOR = 2;
        static final int MODIFIER = 3;

        private final int kind;

        RecordProcedure(int kind) {
            this.kind = kind;
        }

        @Override
        public Object invoke(Object[] args) {
            return make(args);
        }

        @TruffleBoundary
        private SClosure make(Object[] args) {
            if (!(args[1] instanceof SRecordType type)) {
                throw notA("define-record-type", 1, "record type", args[1]);
            }
            String name = Printer.print(args[args.length - 1], Printer.Mode.DISPLAY);
            PrimitiveSpec spec;
            switch (kind) {
                case CONSTRUCTOR: {
                    Object[] fields = Lists.toArray("define-record-type", 2, args[2]);
                    StaticProperty[] given = new StaticProperty[fields.length];
                    boolean[] set = new boolean[type.fieldNames.length];
                    int unsetCount = set.length;
                    for (int i = 0; i < fields.length; i++) {
                        int index = field(type, fields[i]);
                        given[i] = type.properties[index];
                        if (!set[index]) {
                            set[index] = true;
                            unsetCount--;
                        }
                    }
                    StaticProperty[] unset = new StaticProperty[unsetCount];
                    for (int i = 0, j = 0; i < set.length; i++) {
                        if (!set[i]) {
                            unset[j++] = type.properties[i];
                        }
                    }
                    spec = new PrimitiveSpec(name, given.length, given.length, () -> new RecordConstructor(type, given, unset)).allocating();
                    break;
                }
                case PREDICATE:
                    spec = new PrimitiveSpec(name, 1, 1, () -> new RecordPredicate(type));
                    break;
                case ACCESSOR: {
                    StaticProperty property = type.properties[field(type, args[2])];
                    spec = new PrimitiveSpec(name, 1, 1, () -> new RecordAccessor(type, property, name));
                    break;
                }
                default: {
                    StaticProperty property = type.properties[field(type, args[2])];
                    spec = new PrimitiveSpec(name, 2, 2, () -> new RecordModifier(type, property, name));
                    break;
                }
            }
            return PrimitiveRootNode.procedure(SchemeLanguage.get(this), spec);
        }

        private static int field(SRecordType type, Object field) {
            int i = field instanceof SSymbol ? type.fieldIndex(field.toString()) : -1;
            if (i < 0) {
                throw SchemeError.of("define-record-type: not a field of " + type.name, field);
            }
            return i;
        }
    }

    /** A record type's constructor: the fields it has no argument for are unspecified. */
    static final class RecordConstructor extends PrimitiveNode {
        private final SRecordType type;
        private final SRecord.Factory factory;
        @CompilationFinal(dimensions = 1) private final StaticProperty[] given;
        @CompilationFinal(dimensions = 1) private final StaticProperty[] unset;

        RecordConstructor(SRecordType type, StaticProperty[] given, StaticProperty[] unset) {
            this.type = type;
            this.factory = type.factory;
            this.given = given;
            this.unset = unset;
        }

        // The arity is checked before invoke: one argument per given field.
        @Override
        @ExplodeLoop
        public Object invoke(Object[] args) {
            // Unrolled: a compilation bails out if a bound isn't a constant (GraalVM's PartialEvaluation guide).
            CompilerAsserts.partialEvaluationConstant(given.length);
            CompilerAsserts.partialEvaluationConstant(unset.length);
            SRecord record = factory.create(type);
            for (int i = 0; i < given.length; i++) {
                given[i].setObject(record, args[i + 1]);
            }
            for (int i = 0; i < unset.length; i++) {
                unset[i].setObject(record, SConstant.UNSPECIFIED);
            }
            return record;
        }
    }

    /** A record type's predicate. */
    static final class RecordPredicate extends PrimitiveNode {
        private final SRecordType type;

        RecordPredicate(SRecordType type) {
            this.type = type;
        }

        @Override
        public Object invoke(Object[] args) {
            return args[1] instanceof SRecord r && r.type == type;
        }
    }

    /** A field's accessor. */
    static final class RecordAccessor extends PrimitiveNode {
        private final SRecordType type;
        private final StaticProperty property;
        private final String name;

        RecordAccessor(SRecordType type, StaticProperty property, String name) {
            this.type = type;
            this.property = property;
            this.name = name;
        }

        @Override
        public Object invoke(Object[] args) {
            if (args[1] instanceof SRecord r && r.type == type) {
                return property.getObject(r);
            }
            throw notOfType(name, type, args[1]);
        }
    }

    /** A field's modifier. */
    static final class RecordModifier extends PrimitiveNode {
        private final SRecordType type;
        private final StaticProperty property;
        private final String name;

        RecordModifier(SRecordType type, StaticProperty property, String name) {
            this.type = type;
            this.property = property;
            this.name = name;
        }

        @Override
        public Object invoke(Object[] args) {
            if (args[1] instanceof SRecord r && r.type == type) {
                property.setObject(r, args[2]);
                return SConstant.UNSPECIFIED;
            }
            throw notOfType(name, type, args[1]);
        }
    }

    @TruffleBoundary
    private static SchemeError notOfType(String who, SRecordType type, Object value) {
        return notA(who, 1, "record of type " + type.name, value);
    }

    /** {@code who}'s argument number {@code position} isn't {@code what} (design.md D8, "Messages"). */
    @TruffleBoundary
    static SchemeError notA(String who, int position, String what, Object value) {
        return SchemeError.argument(who, position, what, value);
    }

    /**
     * {@code (equal? a b)} (R7RS §6.1): structural equality on pairs, vectors, strings and
     * bytevectors, eqv? elsewhere (so records compare by identity). Adams and Dybvig's interleaved
     * algorithm (design.md D9; "Efficient nondestructive equality checking for trees and graphs",
     * ICFP 2008): a depth-first walk that starts in a fast phase, which compares without remembering
     * anything, and alternates with short slow phases, which put the pairs and vectors they compare
     * into one class of a union-find structure and skip a pair of nodes already in one class. The
     * slow phases make it terminate on circular and shared structure, with the coinductive
     * (bisimulation) reading of equal?; the fast phases keep small and acyclic data free of hashing.
     * The walk is iterative, with an explicit stack (rule 4).
     */
    static final class Equal extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            Object a = args[1];
            Object b = args[2];
            if (a == b) {
                return true;
            }
            if (a instanceof Long x) {
                return b instanceof Long y && x.longValue() == y.longValue();
            }
            if (a instanceof SSymbol) {
                return false;
            }
            return equal(a, b);
        }

        /** Nodes compared in the first fast phase, before any hashing (the paper's k0). */
        private static final int FAST_STEPS = 400;
        /** Unions in each slow phase (the paper's -kb). Pairs of nodes already in one class don't count. */
        private static final int SLOW_STEPS = 40;
        /*
         * Two departures from the paper, for shared and circular data: the walk stays in the slow
         * phase for good once a slow phase has met SLOW_FOREVER_HITS pairs of nodes already in one
         * class, or once it has compared more than SLOW_FOREVER_RATIO nodes per union-find entry
         * (plus SLOW_FOREVER_SLACK). Either way the fast phases would mostly re-walk structure that
         * the slow phase skips. Fresh acyclic data meets no such pairs, and its slow phases add about
         * two entries per 11 nodes, so neither triggers there. Termination doesn't depend on them: a
         * slow phase ends only after SLOW_STEPS unions, and there can only be as many unions as nodes.
         */
        private static final int SLOW_FOREVER_HITS = SLOW_STEPS / 4;
        private static final int SLOW_FOREVER_RATIO = 16;
        private static final int SLOW_FOREVER_SLACK = 1024;

        private static final int DIFFERENT = 0;
        private static final int SAME = 1;
        /** Both are pairs, or both are vectors of one length, and not eq?: compare their elements. */
        private static final int DESCEND = 2;

        @TruffleBoundary
        static boolean equal(Object a, Object b) {
            int top = compareShallow(a, b);
            if (top != DESCEND) {
                return top == SAME;
            }
            Object x = a;
            Object y = b;
            Object[] stack = null;
            int sp = 0;
            // k > 0: fast steps left. k <= 0: in a slow phase, which ends when k reaches -SLOW_STEPS.
            int k = FAST_STEPS;
            long steps = 0;
            int hits = 0;
            int random = 0x2545F491;
            UnionFind classes = null;
            while (true) {
                // Invariant: compareShallow(x, y) == DESCEND.
                steps++;
                boolean known = false;
                if (k > 0) {
                    k--;
                } else {
                    if (classes == null) {
                        classes = new UnionFind();
                    }
                    known = classes.union(x, y);
                    if (known) {
                        hits++;
                    } else if (--k == -SLOW_STEPS) {
                        if (hits < SLOW_FOREVER_HITS && steps <= (long) SLOW_FOREVER_RATIO * classes.size() + SLOW_FOREVER_SLACK) {
                            hits = 0;
                            random ^= random << 13;
                            random ^= random >>> 17;
                            random ^= random << 5;
                            k = 1 + (random >>> 1) % (2 * FAST_STEPS);
                        } else {
                            k = -SLOW_STEPS - 1;  // slow from now on; k only decreases
                        }
                    }
                }
                if (!known) {
                    if (x instanceof SPair p) {
                        SPair q = (SPair) y;
                        int car = compareShallow(p.car, q.car);
                        if (car == DIFFERENT) {
                            return false;
                        }
                        int cdr = compareShallow(p.cdr, q.cdr);
                        if (cdr == DIFFERENT) {
                            return false;
                        }
                        if (car == DESCEND) {
                            // (cons x x) on both sides: one walk of x does (DAGs, in the fast phase).
                            if (cdr == DESCEND && (p.cdr != p.car || q.cdr != q.car)) {
                                stack = push(stack, sp, p.cdr, q.cdr);
                                sp += 2;
                            }
                            x = p.car;
                            y = q.car;
                            continue;
                        }
                        if (cdr == DESCEND) {
                            x = p.cdr;
                            y = q.cdr;
                            continue;
                        }
                    } else {
                        SVector xv = (SVector) x;
                        SVector yv = (SVector) y;
                        if (!xv.isObjects() || !yv.isObjects()) {
                            // Fixnum or flonum storage on one side (M5-06): nothing to look inside.
                            if (!numbersEqual(xv, yv)) {
                                return false;
                            }
                        } else {
                            Object[] xs = xv.objects();
                            Object[] ys = yv.objects();
                            boolean descend = false;
                            for (int i = 0; i < xs.length; i++) {
                                int c = compareShallow(xs[i], ys[i]);
                                if (c == DIFFERENT) {
                                    return false;
                                }
                                descend |= c == DESCEND;
                            }
                            if (descend) {
                                // Every element is now SAME or DESCEND; push the DESCEND ones, first on top,
                                // and a run of one pair of nodes (as in (make-vector n x)) only once.
                                for (int i = xs.length - 1; i >= 0; i--) {
                                    Object u = xs[i];
                                    Object v = ys[i];
                                    if (u != v && (u instanceof SPair || u instanceof SVector) && (i == 0 || xs[i - 1] != u || ys[i - 1] != v)) {
                                        stack = push(stack, sp, u, v);
                                        sp += 2;
                                    }
                                }
                            }
                        }
                    }
                }
                if (sp == 0) {
                    return true;
                }
                y = stack[--sp];
                x = stack[--sp];
                stack[sp] = null;
                stack[sp + 1] = null;
            }
        }

        /**
         * Two vectors of the same length, at least one with fixnum or flonum storage, so neither has an
         * element to look inside: eqv? on each pair of elements.
         */
        private static boolean numbersEqual(SVector x, SVector y) {
            if (x.isLongs() && y.isLongs()) {
                return java.util.Arrays.equals(x.longs(), y.longs());
            }
            if (x.isDoubles() && y.isDoubles()) {
                // Compares the flonums' bits, as eqv? does (Double.equals).
                return java.util.Arrays.equals(x.doubles(), y.doubles());
            }
            for (int i = 0; i < x.length; i++) {
                if (!Eqv.eqv(x.get(i), y.get(i))) {
                    return false;
                }
            }
            return true;
        }

        /** Compares two objects without looking inside pairs and vectors. */
        private static int compareShallow(Object x, Object y) {
            if (x == y) {
                return SAME;
            }
            if (x instanceof SPair) {
                return y instanceof SPair ? DESCEND : DIFFERENT;
            }
            if (x instanceof SVector v) {
                return y instanceof SVector w && v.length == w.length ? DESCEND : DIFFERENT;
            }
            if (x instanceof SString s) {
                return y instanceof SString t && sameString(s, t) ? SAME : DIFFERENT;
            }
            if (x instanceof SBytevector u) {
                return y instanceof SBytevector v && java.util.Arrays.equals(u.bytes, v.bytes) ? SAME : DIFFERENT;
            }
            return Eqv.eqv(x, y) ? SAME : DIFFERENT;
        }

        private static boolean sameString(SString s, SString t) {
            int n = s.length();
            if (n != t.length()) {
                return false;
            }
            for (int i = 0; i < n; i++) {
                if (s.codePointAt(i) != t.codePointAt(i)) {
                    return false;
                }
            }
            return true;
        }

        private static Object[] push(Object[] stack, int sp, Object x, Object y) {
            Object[] s = stack;
            if (s == null) {
                s = new Object[16];
            } else if (sp + 2 > s.length) {
                s = java.util.Arrays.copyOf(s, s.length * 2);
            }
            s[sp] = x;
            s[sp + 1] = y;
            return s;
        }

        /** Union-find over node identities, with union by size and path halving. */
        private static final class UnionFind {
            private final java.util.IdentityHashMap<Object, Node> nodes = new java.util.IdentityHashMap<>();

            private static final class Node {
                Node parent;
                int size = 1;
            }

            int size() {
                return nodes.size();
            }

            /** Puts x and y in one class; returns whether they already were. */
            boolean union(Object x, Object y) {
                Node nx = nodes.get(x);
                Node ny = nodes.get(y);
                if (nx == null && ny == null) {
                    Node n = new Node();
                    n.size = 2;
                    nodes.put(x, n);
                    nodes.put(y, n);
                    return false;
                }
                if (nx == null) {
                    Node root = find(ny);
                    root.size++;
                    nodes.put(x, root);
                    return false;
                }
                if (ny == null) {
                    Node root = find(nx);
                    root.size++;
                    nodes.put(y, root);
                    return false;
                }
                Node rx = find(nx);
                Node ry = find(ny);
                if (rx == ry) {
                    return true;
                }
                if (rx.size < ry.size) {
                    rx.parent = ry;
                    ry.size += rx.size;
                } else {
                    ry.parent = rx;
                    rx.size += ry.size;
                }
                return false;
            }

            private static Node find(Node node) {
                Node n = node;
                while (n.parent != null) {
                    if (n.parent.parent != null) {
                        n.parent = n.parent.parent;
                    }
                    n = n.parent;
                }
                return n;
            }
        }
    }
}
