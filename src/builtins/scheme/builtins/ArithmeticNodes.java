package scheme.builtins;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;

/** Binary arithmetic with fast paths for fixnums (overflow-checked) and flonums. */
final class ArithmeticNodes {

    private ArithmeticNodes() {
    }

    @GenerateInline(false)
    abstract static class AddNode extends Node {
        abstract Object execute(Object a, Object b);

        @Specialization(rewriteOn = ArithmeticException.class)
        static long doLong(long a, long b) {
            return Math.addExact(a, b);
        }

        @Specialization
        static double doDouble(double a, double b) {
            return a + b;
        }

        @Fallback
        static Object doGeneric(Object a, Object b) {
            return Arithmetic.apply("+", Arithmetic.Op.ADD, a, b);
        }
    }

    @GenerateInline(false)
    abstract static class SubNode extends Node {
        abstract Object execute(Object a, Object b);

        @Specialization(rewriteOn = ArithmeticException.class)
        static long doLong(long a, long b) {
            return Math.subtractExact(a, b);
        }

        @Specialization
        static double doDouble(double a, double b) {
            return a - b;
        }

        @Fallback
        static Object doGeneric(Object a, Object b) {
            return Arithmetic.apply("-", Arithmetic.Op.SUB, a, b);
        }
    }

    @GenerateInline(false)
    abstract static class MulNode extends Node {
        abstract Object execute(Object a, Object b);

        @Specialization(rewriteOn = ArithmeticException.class)
        static long doLong(long a, long b) {
            return Math.multiplyExact(a, b);
        }

        @Specialization
        static double doDouble(double a, double b) {
            return a * b;
        }

        @Fallback
        static Object doGeneric(Object a, Object b) {
            return Arithmetic.apply("*", Arithmetic.Op.MUL, a, b);
        }
    }

    /** Signum of a - b. */
    @GenerateInline(false)
    abstract static class CompareNode extends Node {
        abstract int execute(Object a, Object b);

        @Specialization
        static int doLong(long a, long b) {
            return Long.compare(a, b);
        }

        @Specialization
        static int doDouble(double a, double b) {
            return Double.compare(a, b);
        }

        @Fallback
        static int doGeneric(Object a, Object b) {
            return Arithmetic.compare("compare", a, b);
        }
    }
}
