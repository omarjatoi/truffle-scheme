package scheme.builtins;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.reader.NumberParser;
import scheme.runtime.PrimitiveNode;
import scheme.runtime.PrimitiveSpec;
import scheme.runtime.PrimitiveSpec.Result;
import scheme.runtime.SchemeError;
import scheme.runtime.printer.Printer;
import scheme.runtime.value.MultipleValues;
import scheme.runtime.value.SBigInteger;
import scheme.runtime.value.SComplex;
import scheme.runtime.value.SRational;
import scheme.runtime.value.SString;

/**
 * The numeric tower area (R7RS §6.2, {@code (scheme complex)}, {@code (scheme inexact)}): its
 * primitives, all leaves (AGENTS.md rule 1). One of the fan-out areas (AGENTS.md "Areas"): add
 * this area's primitives here and nowhere else. Arithmetic is the generic path behind
 * ArithmeticNodes' {@code long} and {@code double} fast paths.
 */
final class NumberPrimitives {

    private NumberPrimitives() {
    }

    /** A one-argument procedure; its body is a constant, so the compiler inlines it. */
    @FunctionalInterface
    interface F1 {
        Object apply(Object x);
    }

    @FunctionalInterface
    interface F2 {
        Object apply(Object x, Object y);
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
        ADD("+", 0, -1, Result.NEW_OBJECT),
        SUBTRACT("-", 1, -1, Result.NEW_OBJECT),
        MULTIPLY("*", 0, -1, Result.NEW_OBJECT),
        DIVIDE("/", 1, -1, Result.NEW_OBJECT),
        NUMBER_EQ("=", 1, -1),
        NUMBER_LT("<", 1, -1),
        NUMBER_GT(">", 1, -1),
        NUMBER_LE("<=", 1, -1),
        NUMBER_GE(">=", 1, -1),
        // Predicates (R7RS §6.2.6)
        IS_NUMBER("number?", 1, 1),
        IS_COMPLEX("complex?", 1, 1),
        IS_REAL("real?", 1, 1),
        IS_RATIONAL("rational?", 1, 1),
        IS_INTEGER("integer?", 1, 1),
        IS_EXACT_INTEGER("exact-integer?", 1, 1),
        IS_EXACT("exact?", 1, 1),
        IS_INEXACT("inexact?", 1, 1),
        IS_FINITE("finite?", 1, 1),
        IS_INFINITE("infinite?", 1, 1),
        IS_NAN("nan?", 1, 1),
        IS_ZERO("zero?", 1, 1),
        IS_POSITIVE("positive?", 1, 1),
        IS_NEGATIVE("negative?", 1, 1),
        IS_ODD("odd?", 1, 1),
        IS_EVEN("even?", 1, 1),
        MAX("max", 1, -1),
        MIN("min", 1, -1),
        ABS("abs", 1, 1),
        SQUARE("square", 1, 1),
        // Integer division
        QUOTIENT("quotient", 2, 2),
        REMAINDER("remainder", 2, 2),
        MODULO("modulo", 2, 2),
        TRUNCATE_QUOTIENT("truncate-quotient", 2, 2),
        TRUNCATE_REMAINDER("truncate-remainder", 2, 2),
        FLOOR_QUOTIENT("floor-quotient", 2, 2),
        FLOOR_REMAINDER("floor-remainder", 2, 2),
        TRUNCATE_DIVIDE("truncate/", 2, 2),
        FLOOR_DIVIDE("floor/", 2, 2),
        GCD("gcd", 0, -1),
        LCM("lcm", 0, -1),
        NUMERATOR("numerator", 1, 1),
        DENOMINATOR("denominator", 1, 1),
        // Rounding
        FLOOR("floor", 1, 1),
        CEILING("ceiling", 1, 1),
        TRUNCATE("truncate", 1, 1),
        ROUND("round", 1, 1),
        RATIONALIZE("rationalize", 2, 2),
        // Transcendental functions
        EXP("exp", 1, 1),
        LOG("log", 1, 2),
        SIN("sin", 1, 1),
        COS("cos", 1, 1),
        TAN("tan", 1, 1),
        ASIN("asin", 1, 1),
        ACOS("acos", 1, 1),
        ATAN("atan", 1, 2),
        SQRT("sqrt", 1, 1),
        EXACT_INTEGER_SQRT("exact-integer-sqrt", 1, 1),
        EXPT("expt", 2, 2),
        // Complex numbers
        MAKE_RECTANGULAR("make-rectangular", 2, 2),
        MAKE_POLAR("make-polar", 2, 2),
        REAL_PART("real-part", 1, 1),
        IMAG_PART("imag-part", 1, 1),
        MAGNITUDE("magnitude", 1, 1),
        ANGLE("angle", 1, 1),
        // Exactness
        EXACT("exact", 1, 1),
        INEXACT("inexact", 1, 1),
        EXACT_TO_INEXACT("exact->inexact", 1, 1),
        INEXACT_TO_EXACT("inexact->exact", 1, 1),
        // Numbers and strings
        NUMBER_TO_STRING("number->string", 1, 2, Result.NEW_OBJECT),
        STRING_TO_NUMBER("string->number", 1, 2, Result.NEW_OBJECT);

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
                case ADD:
                    return new Add();
                case SUBTRACT:
                    return new Sub();
                case MULTIPLY:
                    return new Mul();
                case DIVIDE:
                    return new Div();
                case NUMBER_EQ:
                    return new Compare("=", Compare.EQ);
                case NUMBER_LT:
                    return new Compare("<", Compare.LT);
                case NUMBER_GT:
                    return new Compare(">", Compare.GT);
                case NUMBER_LE:
                    return new Compare("<=", Compare.LE);
                case NUMBER_GE:
                    return new Compare(">=", Compare.GE);
                case IS_NUMBER:
                    return new TypePredicate(Arithmetic::isNumber);
                case IS_COMPLEX:
                    return new TypePredicate(Arithmetic::isNumber);
                case IS_REAL:
                    return new TypePredicate(Arithmetic::isReal);
                case IS_RATIONAL:
                    return new TypePredicate(x -> Arithmetic.isReal(x) && !(x instanceof Double d && !Double.isFinite(d)));
                case IS_INTEGER:
                    return new TypePredicate(Arithmetic::isInteger);
                case IS_EXACT_INTEGER:
                    return new TypePredicate(Arithmetic::isExactInteger);
                case IS_EXACT:
                    return new Unary(x -> Arithmetic.isExact(Arithmetic.check("exact?", x)));
                case IS_INEXACT:
                    return new Unary(x -> !Arithmetic.isExact(Arithmetic.check("inexact?", x)));
                case IS_FINITE:
                    return new Unary(x -> finite(x, true));
                case IS_INFINITE:
                    return new Unary(x -> infinite(x));
                case IS_NAN:
                    return new Unary(x -> nan(x));
                case IS_ZERO:
                    return new Unary(x -> Arithmetic.numericEquals("zero?", x, 0L));
                case IS_POSITIVE:
                    return new Unary(x -> Arithmetic.compare("positive?", x, 0L) == 1);
                case IS_NEGATIVE:
                    return new Unary(x -> Arithmetic.compare("negative?", x, 0L) == -1);
                case IS_ODD:
                    return new Unary(x -> !even("odd?", x));
                case IS_EVEN:
                    return new Unary(x -> even("even?", x));
                case MAX:
                    return new Extremum("max", 1);
                case MIN:
                    return new Extremum("min", -1);
                case ABS:
                    return new Unary(x -> x instanceof Double d ? Math.abs(d) : Arithmetic.sign("abs", Arithmetic.checkReal("abs", x)) < 0 ? Arithmetic.sub(0L, x) : x);
                case SQUARE:
                    return new Unary(x -> Arithmetic.mul(x, x));
                case QUOTIENT:
                    return new Binary((n, d) -> Arithmetic.divide("quotient", n, d, false)[0]);
                case REMAINDER:
                    return new Binary((n, d) -> Arithmetic.divide("remainder", n, d, false)[1]);
                case MODULO:
                    return new Binary((n, d) -> Arithmetic.divide("modulo", n, d, true)[1]);
                case TRUNCATE_QUOTIENT:
                    return new Binary((n, d) -> Arithmetic.divide("truncate-quotient", n, d, false)[0]);
                case TRUNCATE_REMAINDER:
                    return new Binary((n, d) -> Arithmetic.divide("truncate-remainder", n, d, false)[1]);
                case FLOOR_QUOTIENT:
                    return new Binary((n, d) -> Arithmetic.divide("floor-quotient", n, d, true)[0]);
                case FLOOR_REMAINDER:
                    return new Binary((n, d) -> Arithmetic.divide("floor-remainder", n, d, true)[1]);
                case TRUNCATE_DIVIDE:
                    return new Binary((n, d) -> new MultipleValues(Arithmetic.divide("truncate/", n, d, false)));
                case FLOOR_DIVIDE:
                    return new Binary((n, d) -> new MultipleValues(Arithmetic.divide("floor/", n, d, true)));
                case GCD:
                    return new Fold(0L, Arithmetic::gcd);
                case LCM:
                    return new Fold(1L, Arithmetic::lcm);
                case NUMERATOR:
                    return new Unary(x -> fraction("numerator", x, true));
                case DENOMINATOR:
                    return new Unary(x -> fraction("denominator", x, false));
                case FLOOR:
                    return new Unary(x -> Arithmetic.round("floor", Arithmetic.Rounding.FLOOR, x));
                case CEILING:
                    return new Unary(x -> Arithmetic.round("ceiling", Arithmetic.Rounding.CEILING, x));
                case TRUNCATE:
                    return new Unary(x -> Arithmetic.round("truncate", Arithmetic.Rounding.TRUNCATE, x));
                case ROUND:
                    return new Unary(x -> Arithmetic.round("round", Arithmetic.Rounding.ROUND, x));
                case RATIONALIZE:
                    return new Binary(Arithmetic::rationalize);
                case EXP:
                    return new Unary(Arithmetic::exp);
                case LOG:
                    return new Log();
                case SIN:
                    return new Unary(x -> Arithmetic.trig("sin", x));
                case COS:
                    return new Unary(x -> Arithmetic.trig("cos", x));
                case TAN:
                    return new Unary(x -> Arithmetic.trig("tan", x));
                case ASIN:
                    return new Unary(x -> Arithmetic.trig("asin", x));
                case ACOS:
                    return new Unary(x -> Arithmetic.trig("acos", x));
                case ATAN:
                    return new Atan();
                case SQRT:
                    return new Unary(Arithmetic::sqrt);
                case EXACT_INTEGER_SQRT:
                    return new Unary(NumberPrimitives::exactIntegerSqrt);
                case EXPT:
                    return new Binary(Arithmetic::expt);
                case MAKE_RECTANGULAR:
                    return new Binary((a, b) -> SComplex.make(Arithmetic.checkReal("make-rectangular", a), Arithmetic.checkReal("make-rectangular", b)));
                case MAKE_POLAR:
                    return new Binary(Arithmetic::makePolar);
                case REAL_PART:
                    return new Unary(x -> Arithmetic.realPart(Arithmetic.check("real-part", x)));
                case IMAG_PART:
                    return new Unary(x -> Arithmetic.imagPart(Arithmetic.check("imag-part", x)));
                case MAGNITUDE:
                    return new Unary(Arithmetic::magnitude);
                case ANGLE:
                    return new Unary(Arithmetic::angle);
                case EXACT:
                    return new Unary(x -> Arithmetic.exact("exact", x));
                case INEXACT:
                    return new Unary(x -> Arithmetic.inexact(Arithmetic.check("inexact", x)));
                case EXACT_TO_INEXACT:
                    return new Unary(x -> Arithmetic.inexact(Arithmetic.check("exact->inexact", x)));
                case INEXACT_TO_EXACT:
                    return new Unary(x -> Arithmetic.exact("inexact->exact", x));
                case NUMBER_TO_STRING:
                    return new NumberToString();
                case STRING_TO_NUMBER:
                    return new StringToNumber();
            }
            throw new AssertionError(this);
        }
    }

    static final class Unary extends PrimitiveNode {
        private final F1 f;

        Unary(F1 f) {
            this.f = f;
        }

        @Override
        public Object invoke(Object[] args) {
            return f.apply(args[1]);
        }
    }

    static final class Binary extends PrimitiveNode {
        private final F2 f;

        Binary(F2 f) {
            this.f = f;
        }

        @Override
        public Object invoke(Object[] args) {
            return f.apply(args[1], args[2]);
        }
    }

    /** gcd and lcm: a left fold from the identity, over exact or inexact integers. */
    static final class Fold extends PrimitiveNode {
        private final Object identity;
        private final F2 f;

        Fold(Object identity, F2 f) {
            this.identity = identity;
            this.f = f;
        }

        @Override
        public Object invoke(Object[] args) {
            Object acc = identity;
            for (int i = 1; i < args.length; i++) {
                acc = f.apply(acc, args[i]);
            }
            return acc;
        }
    }

    @TruffleBoundary
    private static boolean finite(Object x, boolean finite) {
        Arithmetic.check(finite ? "finite?" : "infinite?", x);
        double re = Arithmetic.flo(Arithmetic.realPart(x));
        double im = Arithmetic.flo(Arithmetic.imagPart(x));
        return Double.isFinite(re) && Double.isFinite(im);
    }

    @TruffleBoundary
    private static boolean infinite(Object x) {
        Arithmetic.check("infinite?", x);
        return Double.isInfinite(Arithmetic.flo(Arithmetic.realPart(x))) || Double.isInfinite(Arithmetic.flo(Arithmetic.imagPart(x)));
    }

    @TruffleBoundary
    private static boolean nan(Object x) {
        Arithmetic.check("nan?", x);
        return Double.isNaN(Arithmetic.flo(Arithmetic.realPart(x))) || Double.isNaN(Arithmetic.flo(Arithmetic.imagPart(x)));
    }

    @TruffleBoundary
    private static boolean even(String who, Object x) {
        Arithmetic.checkInteger(who, x);
        return x instanceof Double d ? Math.IEEEremainder(d, 2) == 0 : !Arithmetic.numerator(x).testBit(0);
    }

    /** numerator and denominator: of the exact value, inexact for an inexact argument. */
    @TruffleBoundary
    private static Object fraction(String who, Object x, boolean numerator) {
        Arithmetic.checkReal(who, x);
        Object exact = Arithmetic.exact(who, x);
        Object part = SBigInteger.normalize(numerator ? Arithmetic.numerator(exact) : Arithmetic.denominator(exact));
        return x instanceof Double ? Arithmetic.flo(part) : part;
    }

    @TruffleBoundary
    private static Object exactIntegerSqrt(Object k) {
        if (!Arithmetic.isExactInteger(k) || Arithmetic.numerator(k).signum() < 0) {
            throw Arithmetic.notA("exact-integer-sqrt", "non-negative exact integer", k);
        }
        java.math.BigInteger[] sr = Arithmetic.numerator(k).sqrtAndRemainder();
        return new MultipleValues(new Object[]{SBigInteger.normalize(sr[0]), SBigInteger.normalize(sr[1])});
    }

    static final class Add extends PrimitiveNode {
        @Child private ArithmeticNodes.AddNode add = ArithmeticNodesFactory.AddNodeGen.create();

        @Override
        public Object invoke(Object[] args) {
            if (args.length == 1) {
                return 0L;
            }
            Object acc = Arithmetic.check("+", args[1]);
            for (int i = 2; i < args.length; i++) {
                acc = add.execute(acc, args[i]);
            }
            return acc;
        }
    }

    static final class Sub extends PrimitiveNode {
        @Child private ArithmeticNodes.SubNode sub = ArithmeticNodesFactory.SubNodeGen.create();

        @Override
        public Object invoke(Object[] args) {
            if (args.length == 2) {
                // Negation, not 0 - x: (- 0.0) is -0.0.
                if (args[1] instanceof Double d) {
                    return -d;
                } else if (args[1] instanceof SComplex) {
                    return Arithmetic.negate(args[1]);
                }
                return sub.execute(0L, args[1]);
            }
            Object acc = Arithmetic.check("-", args[1]);
            for (int i = 2; i < args.length; i++) {
                acc = sub.execute(acc, args[i]);
            }
            return acc;
        }
    }

    static final class Mul extends PrimitiveNode {
        @Child private ArithmeticNodes.MulNode mul = ArithmeticNodesFactory.MulNodeGen.create();

        @Override
        public Object invoke(Object[] args) {
            if (args.length == 1) {
                return 1L;
            }
            Object acc = Arithmetic.check("*", args[1]);
            for (int i = 2; i < args.length; i++) {
                acc = mul.execute(acc, args[i]);
            }
            return acc;
        }
    }

    /**
     * The comparisons. {@code =} works on any numbers; the orderings on real numbers, and are false
     * when any argument is NaN (IEEE 754). Every argument is checked, even after the result is known.
     */
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
        public Object invoke(Object[] args) {
            if (kind == EQ) {
                Arithmetic.check(name, args[1]);
            } else {
                Arithmetic.checkReal(name, args[1]);
            }
            boolean result = true;
            for (int i = 2; i < args.length; i++) {
                boolean ok;
                if (kind == EQ && (args[i - 1] instanceof SComplex || args[i] instanceof SComplex)) {
                    ok = Arithmetic.numericEquals(name, args[i - 1], args[i]);
                } else {
                    int c = compare.execute(args[i - 1], args[i]);
                    ok = c != Arithmetic.UNORDERED && switch (kind) {
                        case EQ -> c == 0;
                        case LT -> c < 0;
                        case GT -> c > 0;
                        case LE -> c <= 0;
                        default -> c >= 0;
                    };
                }
                result &= ok;
            }
            return result;
        }
    }

    /** {@code (/ z)} is 1/z; {@code (/ z1 z2 ...)} divides left to right. */
    static final class Div extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            if (args.length == 2) {
                return Arithmetic.div(1L, args[1]);
            }
            Object acc = Arithmetic.check("/", args[1]);
            for (int i = 2; i < args.length; i++) {
                acc = Arithmetic.div(acc, args[i]);
            }
            return acc;
        }
    }

    /** max and min: inexact if any argument is (R7RS §6.2.6), NaN if any is NaN. */
    static final class Extremum extends PrimitiveNode {
        private final String name;
        private final int direction;

        Extremum(String name, int direction) {
            this.name = name;
            this.direction = direction;
        }

        @Override
        public Object invoke(Object[] args) {
            return extremum(name, direction, args);
        }

        @TruffleBoundary
        private static Object extremum(String name, int direction, Object[] args) {
            Object best = Arithmetic.checkReal(name, args[1]);
            boolean inexact = best instanceof Double;
            boolean nan = best instanceof Double d && Double.isNaN(d);
            for (int i = 2; i < args.length; i++) {
                Object x = Arithmetic.checkReal(name, args[i]);
                inexact |= x instanceof Double;
                nan |= x instanceof Double d && Double.isNaN(d);
                if (!nan && Arithmetic.compare(name, x, best) == direction) {
                    best = x;
                }
            }
            if (nan) {
                return Double.NaN;
            }
            return inexact ? Arithmetic.flo(best) : best;
        }
    }

    static final class Log extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            Object x = Arithmetic.log(args[1]);
            return args.length == 2 ? x : Arithmetic.div(x, Arithmetic.log(args[2]));
        }
    }

    static final class Atan extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            if (args.length == 2) {
                return Arithmetic.trig("atan", args[1]);
            }
            return atan2(args[1], args[2]);
        }

        @TruffleBoundary
        private static Object atan2(Object y, Object x) {
            Arithmetic.checkReal("atan", y);
            Arithmetic.checkReal("atan", x);
            return StrictMath.atan2(Arithmetic.flo(y), Arithmetic.flo(x));
        }
    }

    /**
     * {@code (number->string z [radix])}: radix 2, 8, 10 or 16. The result reads back as z in
     * that radix (R7RS §6.2.7). Decimals are radix 10 only (§7.1.1), so in another radix an inexact
     * number is written as {@code #i} and the exact rational it equals, its infinities and NaNs as
     * in radix 10.
     */
    static final class NumberToString extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return SString.of(convert(args[1], args.length > 2 ? args[2] : 10L));
        }

        @TruffleBoundary
        private static String convert(Object z, Object radixValue) {
            Arithmetic.check("number->string", z);
            int radix = radix("number->string", radixValue);
            if (radix == 10 || (z instanceof Double d && !Double.isFinite(d))) {
                return Printer.print(z, Printer.Mode.DISPLAY);
            }
            String prefix = Arithmetic.isExact(z) ? "" : "#i";
            if (z instanceof SComplex c) {
                String im = real(c.imag, radix);
                return prefix + real(c.real, radix) + (im.startsWith("-") || im.startsWith("+") ? "" : "+") + im + "i";
            }
            return prefix + real(z, radix);
        }

        private static String real(Object x, int radix) {
            if (x instanceof Double d) {
                if (!Double.isFinite(d)) {
                    return Printer.print(d, Printer.Mode.DISPLAY);
                }
                // -0.0 has no exact counterpart; "#i-0" reads back as 0.0.
                return (d == 0 && 1 / d < 0 ? "-" : "") + real(Arithmetic.exact("number->string", d), radix);
            }
            String n = Arithmetic.numerator(x).toString(radix);
            return x instanceof SRational ? n + "/" + Arithmetic.denominator(x).toString(radix) : n;
        }
    }

    /** {@code (string->number string [radix])}: the reader's number syntax, or #f. */
    static final class StringToNumber extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return parse(args[1], args.length > 2 ? args[2] : 10L);
        }

        @TruffleBoundary
        private static Object parse(Object string, Object radixValue) {
            if (!(string instanceof SString s)) {
                throw Arithmetic.notA("string->number", "string", string);
            }
            Object number = NumberParser.parse(s.toString(), radix("string->number", radixValue));
            return number == null ? (Object) false : number;
        }
    }

    @TruffleBoundary
    static int radix(String who, Object value) {
        if (value instanceof Long r && (r == 2 || r == 8 || r == 10 || r == 16)) {
            return (int) (long) r;
        }
        throw new SchemeError(who + ": radix must be 2, 8, 10 or 16: " + Printer.print(value, Printer.Mode.WRITE));
    }
}
