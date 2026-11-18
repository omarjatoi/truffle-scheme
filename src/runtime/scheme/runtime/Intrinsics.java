package scheme.runtime;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.HostCompilerDirectives.InliningCutoff;
import com.oracle.truffle.api.bytecode.ConstantOperand;
import com.oracle.truffle.api.bytecode.OperationProxy;
import com.oracle.truffle.api.bytecode.StoreBytecodeIndex;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.NeverDefault;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;

import scheme.runtime.value.SChar;
import scheme.runtime.value.SClosure;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SVector;

/**
 * Typed fast paths for the hottest primitives (plan.md M7-03, design.md "Calls and tail calls"):
 * Bytecode DSL operations the compiler emits for a call of {@code car}, {@code +},
 * {@code vector-ref}, ... with a fixed number of arguments, instead of a {@code Call} that runs the
 * primitive in place (M5-09). An operation takes its arguments as operands, so no argument array is
 * made, and its specializations return {@code long}, {@code double} or {@code boolean}, so the
 * Bytecode DSL's boxing elimination keeps those values unboxed from one operation to the next, and
 * in locals: {@code (+ i 1)} in a loop allocates nothing, and an element of a {@code long[]} vector
 * (M5-06) stays a {@code long}.
 *
 * <p>Only a call of a variable that can never be anything but the primitive becomes an operation:
 * one of the primitives' own cells ({@link GlobalCell#isFixed()}), which programs, libraries and
 * the REPL import but can't assign or redefine (assigning an imported variable is a syntax error,
 * and a definition makes a new variable; design.md D7, D15). So an operation needs no guard and no
 * way back to a call. A variable the program binds to a primitive ({@code (define first car)}) is
 * called as before.
 *
 * <p>The fast paths take exactly the cases they can compute without an error; every other case goes
 * to the primitive itself ({@code doGeneric}: its node, as a call site runs it in place), which
 * defines the primitive's semantics: bignums, rationals and complex numbers, mixed exactness, a
 * vector's storage generalized by a store that doesn't fit, and every error. That path stores the
 * bytecode index (the fast paths don't need to: they never fail) and raises the primitive's error
 * in this frame, as {@code Call} does (design.md D8), or in tail position (the {@code tail}
 * operand) lets it leave the frame, as {@code MakeTailCall} does.
 *
 * <p>Fixnum arithmetic overflows into a bignum, never wraps (design.md D1): {@code doLong} is
 * rewritten on the first overflow, after which a pair of fixnums goes to the primitive, which makes
 * the bignum.
 *
 * <p>Operation proxies, so they can live here rather than in {@link SchemeRootNode}: the Bytecode DSL
 * needs each to be a node class with an {@code execute} method too, which nothing else calls.
 */
public final class Intrinsics {

    private Intrinsics() {
    }

    /** What the operations share: the primitive's own node, for every case the fast paths don't take. */
    abstract static class IntrinsicNode extends Node {

        /** The primitive in {@code cell}: a node of the site's own, as {@code Call} runs it in place. */
        @NeverDefault
        static PrimitiveNode primitive(GlobalCell cell) {
            return PrimitiveRootNode.createNode(PrimitiveRootNode.inPlace(((SClosure) cell.valueOrNull()).callTarget));
        }

        /** The primitive procedure: its node's {@code args[0]}. */
        @NeverDefault
        static Object procedure(GlobalCell cell) {
            return cell.valueOrNull();
        }

        /**
         * Whether {@code a / b} is a fixnum: not for a zero divisor, nor for the most negative fixnum
         * over -1.
         */
        static boolean fixnumQuotient(long a, long b) {
            return b != 0 && (a != Long.MIN_VALUE || b != -1);
        }

        /** Whether {@code (/ a b)} is a fixnum: {@code b} divides {@code a}. */
        static boolean exactQuotient(long a, long b) {
            return fixnumQuotient(a, b) && a % b == 0;
        }

        /**
         * Whether {@code x} is a pair, of either class ({@link SPair}, or a literal's
         * {@code LiteralPair}; design.md D1): {@code car}, {@code cdr} and {@code pair?} have one
         * specialization for both, so a literal's pair takes the path a run-time pair takes. In the
         * interpreter, {@code instanceof}: the host compiles the bytecode interpreter's loop with the
         * handlers inlined, and a test only a literal's pair passes would be a branch the host's
         * profile hasn't seen, an uncommon trap and a recompilation of the loop the first time a
         * program's literal reaches it (design.md D1). Compiled, the exact class compared with each
         * of the two, which proves {@code set-car!}'s test on the first.
         */
        static boolean isPair(Object x) {
            if (CompilerDirectives.inInterpreter()) {
                return x instanceof SPair;
            }
            return SPair.asPair(x) != null;
        }

        static boolean inBounds(long k, int length) {
            return k >= 0 && k < length;
        }

        /**
         * The primitive on {@code args} ({@code args[0]} the procedure), as a call site runs it in
         * place. Not in {@code tail} position, its error is raised in this frame (design.md D8),
         * where the operation stored its bytecode index, so the frame's location is the expression
         * that failed; {@code raise} never returns, so that ends in an exception: the uncaught
         * error, or the capture {@code raise} came back with ({@link AbandonedFrame}). In tail
         * position the error leaves the frame, for the call site that called it to raise, as from
         * a primitive {@code MakeTailCall} runs in place: the frame has nothing left to do.
         *
         * <p>One call of the primitive for both: {@code tail} is a constant operand, which folds in
         * partial evaluation but not in the host compilation of the interpreter, where a call in
         * each branch was two call sites (GraalVM's Host Compilation guide, "Deduplicating calls
         * from branches that fold during partial evaluation").
         *
         * <p>Cut from the host's inlining of the bytecode interpreter: the operations' rare path,
         * as {@code Call}'s call path is (M10-02).
         */
        @InliningCutoff
        static Object generic(boolean tail, PrimitiveNode primitive, Object[] args, Node node, Trampoline trampoline) {
            try {
                return primitive.invoke(args);
            } catch (SchemeError e) {
                if (tail) {
                    throw e;
                }
                Object result = SchemeRootNode.raiseHere(e, SchemeContext.get(node), trampoline);
                if (result instanceof Capture capture) {
                    throw new AbandonedFrame(capture);
                }
                throw CompilerDirectives.shouldNotReachHere("raise returned");
            }
        }
    }

    /**
     * {@code eq?} (R7RS §6.1): identity, except that fixnums, flonums, characters and booleans are
     * compared by value, so boxing never shows in a result (design.md D1): a number in a local or in
     * a {@code long[]} or {@code double[]} vector is boxed anew each time it is read as an object.
     */
    public static boolean eq(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a instanceof Long x && b instanceof Long y) {
            return x.longValue() == y.longValue();
        }
        if (a instanceof Double x && b instanceof Double y) {
            return sameDouble(x, y);
        }
        if (a instanceof SChar x && b instanceof SChar y) {
            return x.codePoint == y.codePoint;
        }
        if (a instanceof Boolean x && b instanceof Boolean y) {
            return x.booleanValue() == y.booleanValue();
        }
        return false;
    }

    /** {@code eqv?} on flonums (R7RS §6.1): the same bits, any NaN the same as any other. */
    static boolean sameDouble(double a, double b) {
        return Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
    }

    // ------------------------------------------------------------------------------------------
    // Pairs and lists

    @OperationProxy.Proxyable(storeBytecodeIndex = false)
    @GenerateInline(false)
    @ConstantOperand(type = GlobalCell.class)
    @ConstantOperand(type = boolean.class, name = "tail")
    public abstract static class Car extends IntrinsicNode {
        public abstract Object execute(GlobalCell cell, boolean tail, Object x);

        @Specialization(guards = "isPair(pair)")
        static Object doPair(@SuppressWarnings("unused") GlobalCell cell, @SuppressWarnings("unused") boolean tail, Object pair) {
            return ((SPair) pair).car;
        }

        @Specialization
        @StoreBytecodeIndex
        static Object doGeneric(@SuppressWarnings("unused") GlobalCell cell, boolean tail, Object x,
                        @Bind Node node,
                        @Cached("primitive(cell)") PrimitiveNode primitive,
                        @Cached("procedure(cell)") Object procedure,
                        @Cached Trampoline trampoline) {
            return generic(tail, primitive, new Object[]{procedure, x}, node, trampoline);
        }
    }

    @OperationProxy.Proxyable(storeBytecodeIndex = false)
    @GenerateInline(false)
    @ConstantOperand(type = GlobalCell.class)
    @ConstantOperand(type = boolean.class, name = "tail")
    public abstract static class Cdr extends IntrinsicNode {
        public abstract Object execute(GlobalCell cell, boolean tail, Object x);

        @Specialization(guards = "isPair(pair)")
        static Object doPair(@SuppressWarnings("unused") GlobalCell cell, @SuppressWarnings("unused") boolean tail, Object pair) {
            return ((SPair) pair).cdr;
        }

        @Specialization
        @StoreBytecodeIndex
        static Object doGeneric(@SuppressWarnings("unused") GlobalCell cell, boolean tail, Object x,
                        @Bind Node node,
                        @Cached("primitive(cell)") PrimitiveNode primitive,
                        @Cached("procedure(cell)") Object procedure,
                        @Cached Trampoline trampoline) {
            return generic(tail, primitive, new Object[]{procedure, x}, node, trampoline);
        }
    }

    /** {@code cons} never fails: no primitive to fall back to. It reports the pair, as the primitive does. */
    @OperationProxy.Proxyable(storeBytecodeIndex = false)
    @GenerateInline(false)
    public abstract static class Cons extends IntrinsicNode {
        public abstract Object execute(Object car, Object cdr);

        @Specialization
        static SPair doCons(Object car, Object cdr, @Bind Node node) {
            SPair pair = new SPair(car, cdr);
            Allocations.report(node, pair, Allocations.OBJECT);
            return pair;
        }
    }

    @OperationProxy.Proxyable(storeBytecodeIndex = false)
    @GenerateInline(false)
    public abstract static class IsNull extends IntrinsicNode {
        public abstract boolean execute(Object x);

        @Specialization
        static boolean doCheck(Object x) {
            return x == SConstant.EMPTY_LIST;
        }
    }

    @OperationProxy.Proxyable(storeBytecodeIndex = false)
    @GenerateInline(false)
    public abstract static class IsPair extends IntrinsicNode {
        public abstract boolean execute(Object x);

        @Specialization
        static boolean doCheck(Object x) {
            return isPair(x);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Equivalence and booleans

    @OperationProxy.Proxyable(storeBytecodeIndex = false)
    @GenerateInline(false)
    public abstract static class Eq extends IntrinsicNode {
        public abstract boolean execute(Object a, Object b);

        @Specialization
        static boolean doLong(long a, long b) {
            return a == b;
        }

        @Specialization
        static boolean doDouble(double a, double b) {
            return sameDouble(a, b);
        }

        @Specialization
        static boolean doBoolean(boolean a, boolean b) {
            return a == b;
        }

        @Fallback
        static boolean doObject(Object a, Object b) {
            return eq(a, b);
        }
    }

    /**
     * {@code (assq key 'alist)} or {@code (assv key 'alist)} with a quoted alist (the compiler's
     * ConstantMember, plan.md M9-05): the first entry whose car is {@code eq?} to the key, or #f. The
     * compiler checked the alist: a proper list of at most 16 pairs, and for {@code assv} no key that
     * {@code eq?} compares other than as {@code eqv?} does. One instruction: in the interpreter a loop
     * in Java, where a chain of {@code Eq} operations ran several instructions an entry; compiled, the
     * comparisons one after another, with the keys and entries constants (the node's, made from the
     * alist the first time it runs: the alist is a literal, whose pairs {@code set-car!} and
     * {@code set-cdr!} refuse, design.md D1).
     */
    @OperationProxy.Proxyable(storeBytecodeIndex = false)
    @GenerateInline(false)
    @ConstantOperand(type = Object.class, name = "alist")
    public abstract static class Assoc extends IntrinsicNode {
        public abstract Object execute(Object alist, Object key);

        @Specialization
        @ExplodeLoop
        static Object doMatch(@SuppressWarnings("unused") Object alist, Object key,
                        @Cached(value = "keys(alist)", dimensions = 1) Object[] keys,
                        @Cached(value = "entries(alist)", dimensions = 1) Object[] entries) {
            // Unrolled: a compilation bails out if the bound isn't a constant (GraalVM's PartialEvaluation guide).
            CompilerAsserts.partialEvaluationConstant(keys.length);
            for (int i = 0; i < keys.length; i++) {
                if (eq(key, keys[i])) {
                    return entries[i];
                }
            }
            return false;
        }

        @NeverDefault
        static Object[] entries(Object alist) {
            int n = 0;
            for (Object rest = alist; rest instanceof SPair p; rest = p.cdr) {
                n++;
            }
            Object[] entries = new Object[n];
            Object rest = alist;
            for (int i = 0; i < n; i++) {
                SPair p = (SPair) rest;
                entries[i] = p.car;
                rest = p.cdr;
            }
            return entries;
        }

        @NeverDefault
        static Object[] keys(Object alist) {
            Object[] entries = entries(alist);
            Object[] keys = new Object[entries.length];
            for (int i = 0; i < keys.length; i++) {
                keys[i] = ((SPair) entries[i]).car;
            }
            return keys;
        }
    }

    @OperationProxy.Proxyable(storeBytecodeIndex = false)
    @GenerateInline(false)
    public abstract static class Not extends IntrinsicNode {
        public abstract boolean execute(Object x);

        @Specialization
        static boolean doBoolean(boolean x) {
            return !x;
        }

        @Fallback
        static boolean doOther(@SuppressWarnings("unused") Object x) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Numbers

    @OperationProxy.Proxyable(storeBytecodeIndex = false)
    @GenerateInline(false)
    @ConstantOperand(type = GlobalCell.class)
    @ConstantOperand(type = boolean.class, name = "tail")
    public abstract static class Add extends IntrinsicNode {
        public abstract Object execute(GlobalCell cell, boolean tail, Object a, Object b);

        @Specialization(rewriteOn = ArithmeticException.class)
        static long doLong(@SuppressWarnings("unused") GlobalCell cell, @SuppressWarnings("unused") boolean tail, long a, long b) {
            return Math.addExact(a, b);
        }

        @Specialization
        static double doDouble(@SuppressWarnings("unused") GlobalCell cell, @SuppressWarnings("unused") boolean tail, double a, double b) {
            return a + b;
        }

        @Specialization
        @StoreBytecodeIndex
        static Object doGeneric(@SuppressWarnings("unused") GlobalCell cell, boolean tail, Object a, Object b,
                        @Bind Node node,
                        @Cached("primitive(cell)") PrimitiveNode primitive,
                        @Cached("procedure(cell)") Object procedure,
                        @Cached Trampoline trampoline) {
            return generic(tail, primitive, new Object[]{procedure, a, b}, node, trampoline);
        }
    }

    @OperationProxy.Proxyable(storeBytecodeIndex = false)
    @GenerateInline(false)
    @ConstantOperand(type = GlobalCell.class)
    @ConstantOperand(type = boolean.class, name = "tail")
    public abstract static class Sub extends IntrinsicNode {
        public abstract Object execute(GlobalCell cell, boolean tail, Object a, Object b);

        @Specialization(rewriteOn = ArithmeticException.class)
        static long doLong(@SuppressWarnings("unused") GlobalCell cell, @SuppressWarnings("unused") boolean tail, long a, long b) {
            return Math.subtractExact(a, b);
        }

        @Specialization
        static double doDouble(@SuppressWarnings("unused") GlobalCell cell, @SuppressWarnings("unused") boolean tail, double a, double b) {
            return a - b;
        }

        @Specialization
        @StoreBytecodeIndex
        static Object doGeneric(@SuppressWarnings("unused") GlobalCell cell, boolean tail, Object a, Object b,
                        @Bind Node node,
                        @Cached("primitive(cell)") PrimitiveNode primitive,
                        @Cached("procedure(cell)") Object procedure,
                        @Cached Trampoline trampoline) {
            return generic(tail, primitive, new Object[]{procedure, a, b}, node, trampoline);
        }
    }

    @OperationProxy.Proxyable(storeBytecodeIndex = false)
    @GenerateInline(false)
    @ConstantOperand(type = GlobalCell.class)
    @ConstantOperand(type = boolean.class, name = "tail")
    public abstract static class Mul extends IntrinsicNode {
        public abstract Object execute(GlobalCell cell, boolean tail, Object a, Object b);

        @Specialization(rewriteOn = ArithmeticException.class)
        static long doLong(@SuppressWarnings("unused") GlobalCell cell, @SuppressWarnings("unused") boolean tail, long a, long b) {
            return Math.multiplyExact(a, b);
        }

        @Specialization
        static double doDouble(@SuppressWarnings("unused") GlobalCell cell, @SuppressWarnings("unused") boolean tail, double a, double b) {
            return a * b;
        }

        @Specialization
        @StoreBytecodeIndex
        static Object doGeneric(@SuppressWarnings("unused") GlobalCell cell, boolean tail, Object a, Object b,
                        @Bind Node node,
                        @Cached("primitive(cell)") PrimitiveNode primitive,
                        @Cached("procedure(cell)") Object procedure,
                        @Cached Trampoline trampoline) {
            return generic(tail, primitive, new Object[]{procedure, a, b}, node, trampoline);
        }
    }

    /**
     * {@code /}: a flonum quotient, or a fixnum one that is exact; a rational, an exact zero divisor
     * and the rest are the primitive's.
     */
    @OperationProxy.Proxyable(storeBytecodeIndex = false)
    @GenerateInline(false)
    @ConstantOperand(type = GlobalCell.class)
    @ConstantOperand(type = boolean.class, name = "tail")
    public abstract static class Div extends IntrinsicNode {
        public abstract Object execute(GlobalCell cell, boolean tail, Object a, Object b);

        @Specialization(guards = "exactQuotient(a, b)")
        static long doLong(@SuppressWarnings("unused") GlobalCell cell, @SuppressWarnings("unused") boolean tail, long a, long b) {
            return a / b;
        }

        @Specialization
        static double doDouble(@SuppressWarnings("unused") GlobalCell cell, @SuppressWarnings("unused") boolean tail, double a, double b) {
            return a / b;
        }

        @Specialization
        @StoreBytecodeIndex
        static Object doGeneric(@SuppressWarnings("unused") GlobalCell cell, boolean tail, Object a, Object b,
                        @Bind Node node,
                        @Cached("primitive(cell)") PrimitiveNode primitive,
                        @Cached("procedure(cell)") Object procedure,
                        @Cached Trampoline trampoline) {
            return generic(tail, primitive, new Object[]{procedure, a, b}, node, trampoline);
        }
    }

    /**
     * {@code quotient}, {@code remainder} and {@code modulo} of two fixnums (the division a constant
     * operand), which Java's {@code /}, {@code %} and {@code Math.floorMod} are, but for a zero
     * divisor (an error) and the quotient of the most negative fixnum by -1 (a bignum): those, flonum
     * integers and bignums are the primitive's.
     */
    @OperationProxy.Proxyable(storeBytecodeIndex = false)
    @GenerateInline(false)
    @ConstantOperand(type = GlobalCell.class)
    @ConstantOperand(type = boolean.class, name = "tail")
    @ConstantOperand(type = int.class)
    public abstract static class IntegerDivide extends IntrinsicNode {
        public static final int QUOTIENT = 0;
        public static final int REMAINDER = 1;
        public static final int MODULO = 2;

        public abstract Object execute(GlobalCell cell, boolean tail, int kind, Object a, Object b);

        @Specialization(guards = "fixnumQuotient(a, b)")
        static long doLong(@SuppressWarnings("unused") GlobalCell cell, @SuppressWarnings("unused") boolean tail, int kind, long a, long b) {
            return switch (kind) {
                case QUOTIENT -> a / b;
                case REMAINDER -> a % b;
                default -> Math.floorMod(a, b);
            };
        }

        @Specialization
        @StoreBytecodeIndex
        static Object doGeneric(@SuppressWarnings("unused") GlobalCell cell, boolean tail, @SuppressWarnings("unused") int kind, Object a, Object b,
                        @Bind Node node,
                        @Cached("primitive(cell)") PrimitiveNode primitive,
                        @Cached("procedure(cell)") Object procedure,
                        @Cached Trampoline trampoline) {
            return generic(tail, primitive, new Object[]{procedure, a, b}, node, trampoline);
        }
    }

    /**
     * {@code =}, {@code <}, {@code >}, {@code <=} and {@code >=} of two numbers: one operation, the
     * comparison a constant operand. Java's comparisons of doubles are R7RS's: false when either is
     * a NaN, and {@code -0.0} equals {@code 0.0}.
     */
    @OperationProxy.Proxyable(storeBytecodeIndex = false)
    @GenerateInline(false)
    @ConstantOperand(type = GlobalCell.class)
    @ConstantOperand(type = boolean.class, name = "tail")
    @ConstantOperand(type = int.class)
    public abstract static class Compare extends IntrinsicNode {
        public static final int EQ = 0;
        public static final int LT = 1;
        public static final int GT = 2;
        public static final int LE = 3;
        public static final int GE = 4;

        public abstract Object execute(GlobalCell cell, boolean tail, int kind, Object a, Object b);

        @Specialization
        static boolean doLong(@SuppressWarnings("unused") GlobalCell cell, @SuppressWarnings("unused") boolean tail, int kind, long a, long b) {
            return switch (kind) {
                case EQ -> a == b;
                case LT -> a < b;
                case GT -> a > b;
                case LE -> a <= b;
                default -> a >= b;
            };
        }

        @Specialization
        static boolean doDouble(@SuppressWarnings("unused") GlobalCell cell, @SuppressWarnings("unused") boolean tail, int kind, double a, double b) {
            return switch (kind) {
                case EQ -> a == b;
                case LT -> a < b;
                case GT -> a > b;
                case LE -> a <= b;
                default -> a >= b;
            };
        }

        @Specialization
        @StoreBytecodeIndex
        static Object doGeneric(@SuppressWarnings("unused") GlobalCell cell, boolean tail, @SuppressWarnings("unused") int kind, Object a, Object b,
                        @Bind Node node,
                        @Cached("primitive(cell)") PrimitiveNode primitive,
                        @Cached("procedure(cell)") Object procedure,
                        @Cached Trampoline trampoline) {
            return generic(tail, primitive, new Object[]{procedure, a, b}, node, trampoline);
        }
    }

    @OperationProxy.Proxyable(storeBytecodeIndex = false)
    @GenerateInline(false)
    @ConstantOperand(type = GlobalCell.class)
    @ConstantOperand(type = boolean.class, name = "tail")
    public abstract static class IsZero extends IntrinsicNode {
        public abstract Object execute(GlobalCell cell, boolean tail, Object x);

        @Specialization
        static boolean doLong(@SuppressWarnings("unused") GlobalCell cell, @SuppressWarnings("unused") boolean tail, long x) {
            return x == 0;
        }

        @Specialization
        static boolean doDouble(@SuppressWarnings("unused") GlobalCell cell, @SuppressWarnings("unused") boolean tail, double x) {
            return x == 0.0;
        }

        @Specialization
        @StoreBytecodeIndex
        static Object doGeneric(@SuppressWarnings("unused") GlobalCell cell, boolean tail, Object x,
                        @Bind Node node,
                        @Cached("primitive(cell)") PrimitiveNode primitive,
                        @Cached("procedure(cell)") Object procedure,
                        @Cached Trampoline trampoline) {
            return generic(tail, primitive, new Object[]{procedure, x}, node, trampoline);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Vectors, per storage (plan.md M5-06, SVector): an element of a long[] or double[] vector
    // stays unboxed.

    @OperationProxy.Proxyable(storeBytecodeIndex = false)
    @GenerateInline(false)
    @ConstantOperand(type = GlobalCell.class)
    @ConstantOperand(type = boolean.class, name = "tail")
    public abstract static class VectorRef extends IntrinsicNode {
        public abstract Object execute(GlobalCell cell, boolean tail, Object v, Object k);

        @Specialization(guards = {"v.isLongs()", "inBounds(k, v.longs().length)"})
        static long doLongs(@SuppressWarnings("unused") GlobalCell cell, @SuppressWarnings("unused") boolean tail, SVector v, long k) {
            return v.longs()[(int) k];
        }

        @Specialization(guards = {"v.isDoubles()", "inBounds(k, v.doubles().length)"})
        static double doDoubles(@SuppressWarnings("unused") GlobalCell cell, @SuppressWarnings("unused") boolean tail, SVector v, long k) {
            return v.doubles()[(int) k];
        }

        @Specialization(guards = {"v.isObjects()", "inBounds(k, v.objects().length)"})
        static Object doObjects(@SuppressWarnings("unused") GlobalCell cell, @SuppressWarnings("unused") boolean tail, SVector v, long k) {
            return v.objects()[(int) k];
        }

        @Specialization
        @StoreBytecodeIndex
        static Object doGeneric(@SuppressWarnings("unused") GlobalCell cell, boolean tail, Object v, Object k,
                        @Bind Node node,
                        @Cached("primitive(cell)") PrimitiveNode primitive,
                        @Cached("procedure(cell)") Object procedure,
                        @Cached Trampoline trampoline) {
            return generic(tail, primitive, new Object[]{procedure, v, k}, node, trampoline);
        }
    }

    @OperationProxy.Proxyable(storeBytecodeIndex = false)
    @GenerateInline(false)
    @ConstantOperand(type = GlobalCell.class)
    @ConstantOperand(type = boolean.class, name = "tail")
    public abstract static class VectorSet extends IntrinsicNode {
        public abstract Object execute(GlobalCell cell, boolean tail, Object v, Object k, Object value);

        @Specialization(guards = {"v.isLongs()", "!v.immutable", "inBounds(k, v.longs().length)"})
        static Object doLongs(@SuppressWarnings("unused") GlobalCell cell, @SuppressWarnings("unused") boolean tail, SVector v, long k, long value) {
            v.longs()[(int) k] = value;
            return SConstant.UNSPECIFIED;
        }

        @Specialization(guards = {"v.isDoubles()", "!v.immutable", "inBounds(k, v.doubles().length)"})
        static Object doDoubles(@SuppressWarnings("unused") GlobalCell cell, @SuppressWarnings("unused") boolean tail, SVector v, long k, double value) {
            v.doubles()[(int) k] = value;
            return SConstant.UNSPECIFIED;
        }

        @Specialization(guards = {"v.isObjects()", "!v.immutable", "inBounds(k, v.objects().length)"})
        static Object doObjects(@SuppressWarnings("unused") GlobalCell cell, @SuppressWarnings("unused") boolean tail, SVector v, long k, Object value) {
            v.objects()[(int) k] = value;
            return SConstant.UNSPECIFIED;
        }

        /** A store that doesn't fit the storage (it generalizes it), an immutable vector, an error. */
        @Specialization
        @StoreBytecodeIndex
        static Object doGeneric(@SuppressWarnings("unused") GlobalCell cell, boolean tail, Object v, Object k, Object value,
                        @Bind Node node,
                        @Cached("primitive(cell)") PrimitiveNode primitive,
                        @Cached("procedure(cell)") Object procedure,
                        @Cached Trampoline trampoline) {
            return generic(tail, primitive, new Object[]{procedure, v, k, value}, node, trampoline);
        }
    }

    @OperationProxy.Proxyable(storeBytecodeIndex = false)
    @GenerateInline(false)
    @ConstantOperand(type = GlobalCell.class)
    @ConstantOperand(type = boolean.class, name = "tail")
    public abstract static class VectorLength extends IntrinsicNode {
        public abstract Object execute(GlobalCell cell, boolean tail, Object v);

        @Specialization
        static long doVector(@SuppressWarnings("unused") GlobalCell cell, @SuppressWarnings("unused") boolean tail, SVector v) {
            return v.length;
        }

        @Specialization
        @StoreBytecodeIndex
        static Object doGeneric(@SuppressWarnings("unused") GlobalCell cell, boolean tail, Object v,
                        @Bind Node node,
                        @Cached("primitive(cell)") PrimitiveNode primitive,
                        @Cached("procedure(cell)") Object procedure,
                        @Cached Trampoline trampoline) {
            return generic(tail, primitive, new Object[]{procedure, v}, node, trampoline);
        }
    }
}
