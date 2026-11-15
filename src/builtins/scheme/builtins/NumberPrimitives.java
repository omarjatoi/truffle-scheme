package scheme.builtins;

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
                        new PrimitiveSpec(">=", 1, -1, () -> new Compare(">=", Compare.GE)));
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
}
