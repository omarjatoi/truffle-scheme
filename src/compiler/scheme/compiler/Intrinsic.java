package scheme.compiler;

import java.util.HashMap;
import java.util.Map;

import scheme.runtime.GlobalCell;
import scheme.runtime.Intrinsics;
import scheme.runtime.SchemeRootNodeGen;

/**
 * The primitives a call compiles to an operation of its own (plan.md M7-03,
 * {@link scheme.runtime.Intrinsics}): a call of one of the primitives' own cells
 * ({@link GlobalCell#isFixed()}, whose value never changes) with as many arguments as the operation
 * takes. The cell is the primitive's, whatever the program calls it ({@code (rename (scheme base)
 * (car kar))} too); a variable of the program's own is never one, even when it holds a primitive.
 */
enum Intrinsic {
    CAR("car", 1, false),
    CDR("cdr", 1, false),
    CONS("cons", 2, false),
    IS_NULL("null?", 1, true),
    IS_PAIR("pair?", 1, true),
    EQ("eq?", 2, true),
    NOT("not", 1, true),
    ADD("+", 2, false),
    SUB("-", 2, false),
    MUL("*", 2, false),
    DIV("/", 2, false),
    QUOTIENT("quotient", 2, false),
    REMAINDER("remainder", 2, false),
    MODULO("modulo", 2, false),
    NUM_EQ("=", 2, true),
    LT("<", 2, true),
    GT(">", 2, true),
    LE("<=", 2, true),
    GE(">=", 2, true),
    IS_ZERO("zero?", 1, true),
    VECTOR_REF("vector-ref", 2, false),
    VECTOR_SET("vector-set!", 3, false),
    VECTOR_LENGTH("vector-length", 1, false);

    private static final Map<String, Intrinsic> BY_NAME = new HashMap<>();

    static {
        for (Intrinsic intrinsic : values()) {
            BY_NAME.put(intrinsic.primitive, intrinsic);
        }
    }

    /** The primitive's name: its cell's. */
    final String primitive;
    final int arity;
    /** Whether the primitive is a predicate: its value is always a boolean. */
    final boolean predicate;

    Intrinsic(String primitive, int arity, boolean predicate) {
        this.primitive = primitive;
        this.arity = arity;
        this.predicate = predicate;
    }

    /** The operation for {@code call}, or null if it is to be called. */
    static Intrinsic of(Ast.Call call) {
        if (call.procedure instanceof Ast.GlobalRef g && g.cell.isFixed()) {
            Intrinsic intrinsic = BY_NAME.get(g.cell.name.toString());
            if (intrinsic != null && intrinsic.arity == call.arguments.size()) {
                return intrinsic;
            }
        }
        return null;
    }

    static boolean isIntrinsic(Ast.Call call) {
        return of(call) != null;
    }

    /**
     * Whether {@code test} is a call of a predicate's operation: a boolean already, so a conditional
     * can branch on it without {@code Truthy}.
     */
    static boolean isPredicate(Ast.Expr test) {
        Intrinsic intrinsic = test instanceof Ast.Call call ? of(call) : null;
        return intrinsic != null && intrinsic.predicate;
    }

    /**
     * Begins the operation; its operands, the arguments, follow. {@code tail}: whether the call is
     * in tail position, where the primitive's error leaves the frame ({@code Intrinsics}).
     */
    void begin(SchemeRootNodeGen.Builder b, GlobalCell cell, boolean tail) {
        switch (this) {
            case CAR -> b.beginCar(cell, tail);
            case CDR -> b.beginCdr(cell, tail);
            case CONS -> b.beginCons();
            case IS_NULL -> b.beginIsNull();
            case IS_PAIR -> b.beginIsPair();
            case EQ -> b.beginEq();
            case NOT -> b.beginNot();
            case ADD -> b.beginAdd(cell, tail);
            case SUB -> b.beginSub(cell, tail);
            case MUL -> b.beginMul(cell, tail);
            case DIV -> b.beginDiv(cell, tail);
            case QUOTIENT -> b.beginIntegerDivide(cell, tail, Intrinsics.IntegerDivide.QUOTIENT);
            case REMAINDER -> b.beginIntegerDivide(cell, tail, Intrinsics.IntegerDivide.REMAINDER);
            case MODULO -> b.beginIntegerDivide(cell, tail, Intrinsics.IntegerDivide.MODULO);
            case NUM_EQ -> b.beginCompare(cell, tail, Intrinsics.Compare.EQ);
            case LT -> b.beginCompare(cell, tail, Intrinsics.Compare.LT);
            case GT -> b.beginCompare(cell, tail, Intrinsics.Compare.GT);
            case LE -> b.beginCompare(cell, tail, Intrinsics.Compare.LE);
            case GE -> b.beginCompare(cell, tail, Intrinsics.Compare.GE);
            case IS_ZERO -> b.beginIsZero(cell, tail);
            case VECTOR_REF -> b.beginVectorRef(cell, tail);
            case VECTOR_SET -> b.beginVectorSet(cell, tail);
            case VECTOR_LENGTH -> b.beginVectorLength(cell, tail);
        }
    }

    void end(SchemeRootNodeGen.Builder b) {
        switch (this) {
            case CAR -> b.endCar();
            case CDR -> b.endCdr();
            case CONS -> b.endCons();
            case IS_NULL -> b.endIsNull();
            case IS_PAIR -> b.endIsPair();
            case EQ -> b.endEq();
            case NOT -> b.endNot();
            case ADD -> b.endAdd();
            case SUB -> b.endSub();
            case MUL -> b.endMul();
            case DIV -> b.endDiv();
            case QUOTIENT, REMAINDER, MODULO -> b.endIntegerDivide();
            case NUM_EQ, LT, GT, LE, GE -> b.endCompare();
            case IS_ZERO -> b.endIsZero();
            case VECTOR_REF -> b.endVectorRef();
            case VECTOR_SET -> b.endVectorSet();
            case VECTOR_LENGTH -> b.endVectorLength();
        }
    }
}
