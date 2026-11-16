package scheme.builtins;

import java.util.List;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.reader.NumberParser;
import scheme.runtime.PrimitiveNode;
import scheme.runtime.PrimitiveSpec;
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

    /** This area's primitives: the one list to add to. */
    static List<PrimitiveSpec> primitives() {
        return List.of(
                        new PrimitiveSpec("+", 0, -1, Add::new),
                        new PrimitiveSpec("-", 1, -1, Sub::new),
                        new PrimitiveSpec("*", 0, -1, Mul::new),
                        new PrimitiveSpec("/", 1, -1, Div::new),
                        new PrimitiveSpec("=", 1, -1, () -> new Compare("=", Compare.EQ)),
                        new PrimitiveSpec("<", 1, -1, () -> new Compare("<", Compare.LT)),
                        new PrimitiveSpec(">", 1, -1, () -> new Compare(">", Compare.GT)),
                        new PrimitiveSpec("<=", 1, -1, () -> new Compare("<=", Compare.LE)),
                        new PrimitiveSpec(">=", 1, -1, () -> new Compare(">=", Compare.GE)),
                        // Predicates (R7RS §6.2.6)
                        new PrimitiveSpec("number?", 1, 1, () -> new TypePredicate(Arithmetic::isNumber)),
                        new PrimitiveSpec("complex?", 1, 1, () -> new TypePredicate(Arithmetic::isNumber)),
                        new PrimitiveSpec("real?", 1, 1, () -> new TypePredicate(Arithmetic::isReal)),
                        new PrimitiveSpec("rational?", 1, 1, () -> new TypePredicate(x -> Arithmetic.isReal(x) && !(x instanceof Double d && !Double.isFinite(d)))),
                        new PrimitiveSpec("integer?", 1, 1, () -> new TypePredicate(Arithmetic::isInteger)),
                        new PrimitiveSpec("exact-integer?", 1, 1, () -> new TypePredicate(Arithmetic::isExactInteger)),
                        unary("exact?", x -> Arithmetic.isExact(Arithmetic.check("exact?", x))),
                        unary("inexact?", x -> !Arithmetic.isExact(Arithmetic.check("inexact?", x))),
                        unary("finite?", x -> finite(x, true)),
                        unary("infinite?", x -> infinite(x)),
                        unary("nan?", x -> nan(x)),
                        unary("zero?", x -> Arithmetic.numericEquals("zero?", x, 0L)),
                        unary("positive?", x -> Arithmetic.compare("positive?", x, 0L) == 1),
                        unary("negative?", x -> Arithmetic.compare("negative?", x, 0L) == -1),
                        unary("odd?", x -> !even("odd?", x)),
                        unary("even?", x -> even("even?", x)),
                        new PrimitiveSpec("max", 1, -1, () -> new Extremum("max", 1)),
                        new PrimitiveSpec("min", 1, -1, () -> new Extremum("min", -1)),
                        unary("abs", x -> Arithmetic.sign("abs", Arithmetic.checkReal("abs", x)) < 0 ? Arithmetic.sub(0L, x) : x),
                        unary("square", x -> Arithmetic.mul(x, x)),
                        // Integer division
                        binary("quotient", (n, d) -> Arithmetic.divide("quotient", n, d, false)[0]),
                        binary("remainder", (n, d) -> Arithmetic.divide("remainder", n, d, false)[1]),
                        binary("modulo", (n, d) -> Arithmetic.divide("modulo", n, d, true)[1]),
                        binary("truncate-quotient", (n, d) -> Arithmetic.divide("truncate-quotient", n, d, false)[0]),
                        binary("truncate-remainder", (n, d) -> Arithmetic.divide("truncate-remainder", n, d, false)[1]),
                        binary("floor-quotient", (n, d) -> Arithmetic.divide("floor-quotient", n, d, true)[0]),
                        binary("floor-remainder", (n, d) -> Arithmetic.divide("floor-remainder", n, d, true)[1]),
                        binary("truncate/", (n, d) -> new MultipleValues(Arithmetic.divide("truncate/", n, d, false))),
                        binary("floor/", (n, d) -> new MultipleValues(Arithmetic.divide("floor/", n, d, true))),
                        new PrimitiveSpec("gcd", 0, -1, () -> new Fold(0L, Arithmetic::gcd)),
                        new PrimitiveSpec("lcm", 0, -1, () -> new Fold(1L, Arithmetic::lcm)),
                        unary("numerator", x -> fraction("numerator", x, true)),
                        unary("denominator", x -> fraction("denominator", x, false)),
                        // Rounding
                        unary("floor", x -> Arithmetic.round("floor", Arithmetic.Rounding.FLOOR, x)),
                        unary("ceiling", x -> Arithmetic.round("ceiling", Arithmetic.Rounding.CEILING, x)),
                        unary("truncate", x -> Arithmetic.round("truncate", Arithmetic.Rounding.TRUNCATE, x)),
                        unary("round", x -> Arithmetic.round("round", Arithmetic.Rounding.ROUND, x)),
                        binary("rationalize", Arithmetic::rationalize),
                        // Transcendental functions
                        unary("exp", Arithmetic::exp),
                        new PrimitiveSpec("log", 1, 2, Log::new),
                        unary("sin", x -> Arithmetic.trig("sin", x)),
                        unary("cos", x -> Arithmetic.trig("cos", x)),
                        unary("tan", x -> Arithmetic.trig("tan", x)),
                        unary("asin", x -> Arithmetic.trig("asin", x)),
                        unary("acos", x -> Arithmetic.trig("acos", x)),
                        new PrimitiveSpec("atan", 1, 2, Atan::new),
                        unary("sqrt", Arithmetic::sqrt),
                        unary("exact-integer-sqrt", NumberPrimitives::exactIntegerSqrt),
                        binary("expt", Arithmetic::expt),
                        // Complex numbers
                        binary("make-rectangular", (a, b) -> SComplex.make(Arithmetic.checkReal("make-rectangular", a), Arithmetic.checkReal("make-rectangular", b))),
                        binary("make-polar", Arithmetic::makePolar),
                        unary("real-part", x -> Arithmetic.realPart(Arithmetic.check("real-part", x))),
                        unary("imag-part", x -> Arithmetic.imagPart(Arithmetic.check("imag-part", x))),
                        unary("magnitude", Arithmetic::magnitude),
                        unary("angle", Arithmetic::angle),
                        // Exactness
                        unary("exact", x -> Arithmetic.exact("exact", x)),
                        unary("inexact", x -> Arithmetic.inexact(Arithmetic.check("inexact", x))),
                        unary("exact->inexact", x -> Arithmetic.inexact(Arithmetic.check("exact->inexact", x))),
                        unary("inexact->exact", x -> Arithmetic.exact("inexact->exact", x)),
                        // Numbers and strings
                        new PrimitiveSpec("number->string", 1, 2, NumberToString::new),
                        new PrimitiveSpec("string->number", 1, 2, StringToNumber::new));
    }

    private static PrimitiveSpec unary(String name, F1 f) {
        return new PrimitiveSpec(name, 1, 1, () -> new Unary(f));
    }

    private static PrimitiveSpec binary(String name, F2 f) {
        return new PrimitiveSpec(name, 2, 2, () -> new Binary(f));
    }

    static final class Unary extends PrimitiveNode {
        private final F1 f;

        Unary(F1 f) {
            this.f = f;
        }

        @Override
        public Object execute(Object[] args) {
            return f.apply(args[0]);
        }
    }

    static final class Binary extends PrimitiveNode {
        private final F2 f;

        Binary(F2 f) {
            this.f = f;
        }

        @Override
        public Object execute(Object[] args) {
            return f.apply(args[0], args[1]);
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
        public Object execute(Object[] args) {
            Object acc = identity;
            for (Object a : args) {
                acc = f.apply(acc, a);
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
        public Object execute(Object[] args) {
            if (kind == EQ) {
                Arithmetic.check(name, args[0]);
            } else {
                Arithmetic.checkReal(name, args[0]);
            }
            boolean result = true;
            for (int i = 1; i < args.length; i++) {
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
        public Object execute(Object[] args) {
            if (args.length == 1) {
                return Arithmetic.div(1L, args[0]);
            }
            Object acc = Arithmetic.check("/", args[0]);
            for (int i = 1; i < args.length; i++) {
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
        public Object execute(Object[] args) {
            return extremum(name, direction, args);
        }

        @TruffleBoundary
        private static Object extremum(String name, int direction, Object[] args) {
            Object best = Arithmetic.checkReal(name, args[0]);
            boolean inexact = best instanceof Double;
            boolean nan = best instanceof Double d && Double.isNaN(d);
            for (int i = 1; i < args.length; i++) {
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
        public Object execute(Object[] args) {
            Object x = Arithmetic.log(args[0]);
            return args.length == 1 ? x : Arithmetic.div(x, Arithmetic.log(args[1]));
        }
    }

    static final class Atan extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            if (args.length == 1) {
                return Arithmetic.trig("atan", args[0]);
            }
            return atan2(args[0], args[1]);
        }

        @TruffleBoundary
        private static Object atan2(Object y, Object x) {
            Arithmetic.checkReal("atan", y);
            Arithmetic.checkReal("atan", x);
            return Math.atan2(Arithmetic.flo(y), Arithmetic.flo(x));
        }
    }

    /** {@code (number->string z [radix])}: radix 2, 8, 10 or 16; inexact numbers in radix 10. */
    static final class NumberToString extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return SString.of(convert(args[0], args.length > 1 ? args[1] : 10L));
        }

        @TruffleBoundary
        private static String convert(Object z, Object radixValue) {
            Arithmetic.check("number->string", z);
            int radix = radix("number->string", radixValue);
            if (radix == 10 || !Arithmetic.isExact(z)) {
                return Printer.print(z, Printer.Mode.DISPLAY);
            }
            if (z instanceof SComplex c) {
                String im = real(c.imag, radix);
                return real(c.real, radix) + (im.startsWith("-") ? "" : "+") + im + "i";
            }
            return real(z, radix);
        }

        private static String real(Object x, int radix) {
            String n = Arithmetic.numerator(x).toString(radix);
            return x instanceof SRational ? n + "/" + Arithmetic.denominator(x).toString(radix) : n;
        }
    }

    /** {@code (string->number string [radix])}: the reader's number syntax, or #f. */
    static final class StringToNumber extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return parse(args[0], args.length > 1 ? args[1] : 10L);
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
