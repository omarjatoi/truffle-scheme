package scheme.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.runtime.SchemeError;

import scheme.runtime.printer.Printer;

import scheme.runtime.value.MultipleValues;

import scheme.runtime.value.SString;

import scheme.runtime.value.SRational;

import scheme.runtime.value.SBigInteger;

import scheme.runtime.value.SComplex;

import java.math.BigInteger;

import java.util.List;

import scheme.runtime.PrimitiveNode;
import scheme.runtime.PrimitiveSpec;

/**
 * The numeric tower area (R7RS §6.2): its primitives, all leaves (AGENTS.md rule 1). One of the
 * fan-out areas (AGENTS.md "Areas"): add this area's primitives here and nowhere else. Arithmetic and
 * ArithmeticNodes hold its shared machinery.
 */
final class NumberPrimitives {

    private NumberPrimitives() {
    }

    /** This area's primitives: the one list to add to. */
    static List<PrimitiveSpec> primitives() {
        return List.of(
                        new PrimitiveSpec("+", 0, -1, Add::new),
                        new PrimitiveSpec("-", 1, -1, Sub::new),
                        new PrimitiveSpec("*", 0, -1, Mul::new),
                        new PrimitiveSpec("=", 1, -1, () -> new Compare("=", Compare.EQ)),
                        new PrimitiveSpec("<", 1, -1, () -> new Compare("<", Compare.LT)),
                        new PrimitiveSpec(">", 1, -1, () -> new Compare(">", Compare.GT)),
                        new PrimitiveSpec("<=", 1, -1, () -> new Compare("<=", Compare.LE)),
                        new PrimitiveSpec(">=", 1, -1, () -> new Compare(">=", Compare.GE)),
                        new PrimitiveSpec("/", 1, -1, Div::new),
                        new PrimitiveSpec("number?", 1, 1, () -> new TypePredicate(x -> Arithmetic.isReal(x) || x instanceof SComplex)),
                        new PrimitiveSpec("complex?", 1, 1, () -> new TypePredicate(x -> Arithmetic.isReal(x) || x instanceof SComplex)),
                        new PrimitiveSpec("real?", 1, 1, () -> new TypePredicate(Arithmetic::isReal)),
                        new PrimitiveSpec("rational?", 1, 1, () -> new TypePredicate(x -> Arithmetic.isExact(x) || x instanceof Double d && Double.isFinite(d))),
                        new PrimitiveSpec("integer?", 1, 1, () -> new TypePredicate(NumberPrimitives::isInteger)),
                        new PrimitiveSpec("exact-integer?", 1, 1, () -> new TypePredicate(x -> x instanceof Long || x instanceof SBigInteger)),
                        new PrimitiveSpec("exact?", 1, 1, () -> new Unary("exact?", Unary.EXACT)),
                        new PrimitiveSpec("inexact?", 1, 1, () -> new Unary("inexact?", Unary.INEXACT)),
                        new PrimitiveSpec("zero?", 1, 1, () -> new Unary("zero?", Unary.ZERO)),
                        new PrimitiveSpec("positive?", 1, 1, () -> new Unary("positive?", Unary.POSITIVE)),
                        new PrimitiveSpec("negative?", 1, 1, () -> new Unary("negative?", Unary.NEGATIVE)),
                        new PrimitiveSpec("odd?", 1, 1, () -> new Unary("odd?", Unary.ODD)),
                        new PrimitiveSpec("even?", 1, 1, () -> new Unary("even?", Unary.EVEN)),
                        new PrimitiveSpec("abs", 1, 1, () -> new Unary("abs", Unary.ABS)),
                        new PrimitiveSpec("square", 1, 1, () -> new Unary("square", Unary.SQUARE)),
                        new PrimitiveSpec("real-part", 1, 1, () -> new Unary("real-part", Unary.REAL_PART)),
                        new PrimitiveSpec("imag-part", 1, 1, () -> new Unary("imag-part", Unary.IMAG_PART)),
                        new PrimitiveSpec("expt", 2, 2, Expt::new),
                        new PrimitiveSpec("exact-integer-sqrt", 1, 1, ExactIntegerSqrt::new),
                        new PrimitiveSpec("number->string", 1, 2, NumberToString::new),
                        new PrimitiveSpec("exp", 1, 1, () -> new Inexact("exp", Math::exp)),
                        new PrimitiveSpec("sin", 1, 1, () -> new Inexact("sin", Math::sin)),
                        new PrimitiveSpec("cos", 1, 1, () -> new Inexact("cos", Math::cos)),
                        new PrimitiveSpec("tan", 1, 1, () -> new Inexact("tan", Math::tan)),
                        new PrimitiveSpec("asin", 1, 1, () -> new Inexact("asin", Math::asin)),
                        new PrimitiveSpec("acos", 1, 1, () -> new Inexact("acos", Math::acos)),
                        new PrimitiveSpec("log", 1, 2, Log::new),
                        new PrimitiveSpec("atan", 1, 2, Atan::new),
                        new PrimitiveSpec("sqrt", 1, 1, Sqrt::new),
                        new PrimitiveSpec("exact", 1, 1, Exact::new),
                        new PrimitiveSpec("inexact", 1, 1, () -> new Inexact("inexact", x -> x)),
                        new PrimitiveSpec("finite?", 1, 1, () -> new TypePredicate(x -> !(x instanceof Double d) || Double.isFinite(d))),
                        new PrimitiveSpec("infinite?", 1, 1, () -> new TypePredicate(x -> x instanceof Double d && Double.isInfinite(d))),
                        new PrimitiveSpec("nan?", 1, 1, () -> new TypePredicate(x -> x instanceof Double d && Double.isNaN(d))));
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

    static boolean isInteger(Object x) {
        return x instanceof Long || x instanceof SBigInteger || x instanceof Double d && Double.isFinite(d) && d == Math.rint(d);
    }

    /** {@code (/ z)} is 1/z; {@code (/ z1 z2 ...)} divides left to right. */
    static final class Div extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            if (args.length == 1) {
                return Arithmetic.apply("/", Arithmetic.Op.DIV, 1L, args[0]);
            }
            Object acc = Arithmetic.check("/", args[0]);
            for (int i = 1; i < args.length; i++) {
                acc = Arithmetic.apply("/", Arithmetic.Op.DIV, acc, args[i]);
            }
            return acc;
        }
    }

    /** One-argument operations on real numbers (R7RS §6.2.6). */
    static final class Unary extends PrimitiveNode {
        static final int EXACT = 0;
        static final int INEXACT = 1;
        static final int ZERO = 2;
        static final int POSITIVE = 3;
        static final int NEGATIVE = 4;
        static final int ODD = 5;
        static final int EVEN = 6;
        static final int ABS = 7;
        static final int SQUARE = 8;
        static final int REAL_PART = 9;
        static final int IMAG_PART = 10;

        private final String name;
        private final int op;

        Unary(String name, int op) {
            this.name = name;
            this.op = op;
        }

        @Override
        public Object execute(Object[] args) {
            return apply(name, op, args[0]);
        }

        @TruffleBoundary
        private static Object apply(String name, int op, Object x) {
            Arithmetic.check(name, x);
            switch (op) {
                case EXACT:
                    return Arithmetic.isExact(x);
                case INEXACT:
                    return x instanceof Double;
                case ZERO:
                    return Arithmetic.compare(name, x, 0L) == 0;
                case POSITIVE:
                    return Arithmetic.compare(name, x, 0L) > 0;
                case NEGATIVE:
                    return Arithmetic.compare(name, x, 0L) < 0;
                case ODD:
                case EVEN: {
                    if (!isInteger(x)) {
                        throw ControlPrimitives.notA(name, "integer", x);
                    }
                    boolean even = x instanceof Double d ? Math.IEEEremainder(d, 2) == 0 : Arithmetic.numerator(x).testBit(0) == false;
                    return op == EVEN ? even : !even;
                }
                case ABS:
                    return Arithmetic.compare(name, x, 0L) < 0 ? Arithmetic.apply(name, Arithmetic.Op.SUB, 0L, x) : x;
                case SQUARE:
                    return Arithmetic.apply(name, Arithmetic.Op.MUL, x, x);
                case REAL_PART:
                    return x;
                default:
                    return 0L;
            }
        }
    }

    /** {@code (expt z1 z2)}: exact when both are exact and z2 is an integer. */
    static final class Expt extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return expt(args[0], args[1]);
        }

        @TruffleBoundary
        private static Object expt(Object base, Object power) {
            Arithmetic.check("expt", base);
            Arithmetic.check("expt", power);
            if (Arithmetic.isExact(base) && (power instanceof Long || power instanceof SBigInteger)) {
                BigInteger p = Arithmetic.numerator(power);
                if (p.bitLength() > 31) {
                    throw new SchemeError("expt: exponent too large: " + p);
                }
                int e = p.intValue();
                BigInteger n = Arithmetic.numerator(base).pow(Math.abs(e));
                BigInteger d = Arithmetic.denominator(base).pow(Math.abs(e));
                return e >= 0 ? SRational.make(n, d) : SRational.make(d, n);
            }
            return Math.pow(Arithmetic.flo(base), Arithmetic.flo(power));
        }
    }

    /** {@code (exact-integer-sqrt k)}: s and k - s^2, as two values. */
    static final class ExactIntegerSqrt extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return sqrt(args[0]);
        }

        @TruffleBoundary
        private static Object sqrt(Object k) {
            if (!(k instanceof Long || k instanceof SBigInteger) || Arithmetic.numerator(k).signum() < 0) {
                throw ControlPrimitives.notA("exact-integer-sqrt", "non-negative exact integer", k);
            }
            BigInteger[] sr = Arithmetic.numerator(k).sqrtAndRemainder();
            return new MultipleValues(new Object[]{SBigInteger.normalize(sr[0]), SBigInteger.normalize(sr[1])});
        }
    }

    /** {@code (number->string z [radix])}: radix 2, 8, 10 or 16 for exact numbers. */
    static final class NumberToString extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return SString.of(convert(args[0], args.length > 1 ? args[1] : 10L));
        }

        @TruffleBoundary
        private static String convert(Object z, Object radixValue) {
            Arithmetic.check("number->string", z);
            if (!(radixValue instanceof Long r) || (r != 2 && r != 8 && r != 10 && r != 16)) {
                throw ControlPrimitives.notA("number->string", "radix (2, 8, 10 or 16)", radixValue);
            }
            int radix = (int) (long) r;
            if (Arithmetic.isExact(z)) {
                String n = Arithmetic.numerator(z).toString(radix);
                return z instanceof SRational ? n + "/" + Arithmetic.denominator(z).toString(radix) : n;
            }
            if (radix != 10) {
                throw new SchemeError("number->string: inexact numbers only in radix 10 (until M4)");
            }
            return Printer.print(z, Printer.Mode.DISPLAY);
        }
    }

    /**
     * A function of a real argument with an inexact real result (R7RS §6.2.6). Complex arguments and
     * results (branch cuts) arrive with the rest of the numeric tower (M4).
     */
    static final class Inexact extends PrimitiveNode {
        private final String name;
        private final java.util.function.DoubleUnaryOperator f;

        Inexact(String name, java.util.function.DoubleUnaryOperator f) {
            this.name = name;
            this.f = f;
        }

        @Override
        public Object execute(Object[] args) {
            return f.applyAsDouble(Arithmetic.flo(Arithmetic.check(name, args[0])));
        }
    }

    /** {@code (log z [base])}. */
    static final class Log extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            double x = Math.log(Arithmetic.flo(Arithmetic.check("log", args[0])));
            return args.length == 1 ? x : x / Math.log(Arithmetic.flo(Arithmetic.check("log", args[1])));
        }
    }

    /** {@code (atan y [x])}. */
    static final class Atan extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            double y = Arithmetic.flo(Arithmetic.check("atan", args[0]));
            return args.length == 1 ? Math.atan(y) : Math.atan2(y, Arithmetic.flo(Arithmetic.check("atan", args[1])));
        }
    }

    /** {@code (sqrt z)}: exact for an exact perfect square (or a ratio of them). */
    static final class Sqrt extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return sqrt(args[0]);
        }

        @TruffleBoundary
        private static Object sqrt(Object z) {
            Arithmetic.check("sqrt", z);
            if (Arithmetic.isExact(z) && Arithmetic.numerator(z).signum() >= 0) {
                BigInteger[] n = Arithmetic.numerator(z).sqrtAndRemainder();
                BigInteger[] d = Arithmetic.denominator(z).sqrtAndRemainder();
                if (n[1].signum() == 0 && d[1].signum() == 0) {
                    return SRational.make(n[0], d[0]);
                }
            }
            return Math.sqrt(Arithmetic.flo(z));
        }
    }

    /** {@code (exact z)}: the exactly equal rational for a flonum (design.md D1). */
    static final class Exact extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return exact(args[0]);
        }

        @TruffleBoundary
        private static Object exact(Object z) {
            Arithmetic.check("exact", z);
            if (!(z instanceof Double d)) {
                return z;
            }
            if (!Double.isFinite(d)) {
                throw ControlPrimitives.notA("exact", "finite number", z);
            }
            java.math.BigDecimal b = new java.math.BigDecimal(d);
            return b.scale() <= 0 ? SBigInteger.normalize(b.toBigIntegerExact())
                            : SRational.make(b.unscaledValue(), BigInteger.TEN.pow(b.scale()));
        }
    }
}
