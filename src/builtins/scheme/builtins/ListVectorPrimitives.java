package scheme.builtins;

import java.util.List;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.runtime.PrimitiveNode;
import scheme.runtime.PrimitiveSpec;
import scheme.runtime.SchemeError;
import scheme.runtime.printer.Printer;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SVector;

/**
 * The lists and vectors area (R7RS §6.4, §6.8): its primitives, all leaves (AGENTS.md rule 1). One of the
 * fan-out areas (AGENTS.md "Areas"): add this area's primitives here and nowhere else. Lists holds
 * helpers for taking lists apart.
 */
final class ListVectorPrimitives {

    private ListVectorPrimitives() {
    }

    /** This area's primitives: the one list to add to. */
    static List<PrimitiveSpec> primitives() {
        return List.of(
                        new PrimitiveSpec("cons", 2, 2, Cons::new),
                        new PrimitiveSpec("car", 1, 1, () -> new PairAccess("car", true)),
                        new PrimitiveSpec("cdr", 1, 1, () -> new PairAccess("cdr", false)),
                        new PrimitiveSpec("set-car!", 2, 2, () -> new SetPair(true)),
                        new PrimitiveSpec("set-cdr!", 2, 2, () -> new SetPair(false)),
                        new PrimitiveSpec("null?", 1, 1, () -> new TypePredicate(x -> x == SConstant.EMPTY_LIST)),
                        new PrimitiveSpec("pair?", 1, 1, () -> new TypePredicate(x -> x instanceof SPair)),
                        new PrimitiveSpec("append", 0, -1, Append::new),
                        new PrimitiveSpec("vector", 0, -1, MakeVector::new),
                        new PrimitiveSpec("vector?", 1, 1, () -> new TypePredicate(x -> x instanceof SVector)),
                        new PrimitiveSpec("make-vector", 1, 2, MakeVectorOfLength::new),
                        new PrimitiveSpec("vector-length", 1, 1, () -> new VectorOp(VectorOp.LENGTH)),
                        new PrimitiveSpec("vector-ref", 2, 2, () -> new VectorOp(VectorOp.REF)),
                        new PrimitiveSpec("vector-set!", 3, 3, () -> new VectorOp(VectorOp.SET)),
                        new PrimitiveSpec("list->vector", 1, 1, ListToVector::new));
    }

    static final class Cons extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return new SPair(args[0], args[1]);
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

    static final class SetPair extends PrimitiveNode {
        private final boolean car;

        SetPair(boolean car) {
            this.car = car;
        }

        @Override
        public Object execute(Object[] args) {
            if (!(args[0] instanceof SPair p)) {
                throw Lists.notAPair(car ? "set-car!" : "set-cdr!", args[0]);
            }
            if (car) {
                p.car = args[1];
            } else {
                p.cdr = args[1];
            }
            return SConstant.UNSPECIFIED;
        }
    }

    /** {@code (append list ... obj)}: copies every list but the last, which is shared. */
    static final class Append extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return append(args);
        }

        @TruffleBoundary
        private static Object append(Object[] args) {
            if (args.length == 0) {
                return SConstant.EMPTY_LIST;
            }
            Object result = args[args.length - 1];
            for (int i = args.length - 2; i >= 0; i--) {
                Object[] items = Lists.toArray("append", args[i]);
                for (int k = items.length - 1; k >= 0; k--) {
                    result = new SPair(items[k], result);
                }
            }
            return result;
        }
    }

    static final class MakeVector extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return new SVector(args.clone(), false);
        }
    }

    /** {@code (list->vector list)}. */
    static final class ListToVector extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return new SVector(Lists.toArray("list->vector", args[0]), false);
        }
    }

    /** {@code (make-vector k [fill])}. */
    static final class MakeVectorOfLength extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            if (!(args[0] instanceof Long k) || k < 0 || k > Integer.MAX_VALUE - 8) {
                throw ControlPrimitives.notA("make-vector", "valid vector length", args[0]);
            }
            Object[] elements = new Object[(int) (long) k];
            java.util.Arrays.fill(elements, args.length > 1 ? args[1] : SConstant.UNSPECIFIED);
            return new SVector(elements, false);
        }
    }

    static final class VectorOp extends PrimitiveNode {
        static final int LENGTH = 0;
        static final int REF = 1;
        static final int SET = 2;

        private final int op;

        VectorOp(int op) {
            this.op = op;
        }

        @Override
        public Object execute(Object[] args) {
            String name = op == LENGTH ? "vector-length" : op == REF ? "vector-ref" : "vector-set!";
            if (!(args[0] instanceof SVector v)) {
                throw ControlPrimitives.notA(name, "vector", args[0]);
            }
            if (op == LENGTH) {
                return (long) v.elements.length;
            }
            if (!(args[1] instanceof Long k) || k < 0 || k >= v.elements.length) {
                throw ControlPrimitives.notA(name, "valid index for a vector of length " + v.elements.length, args[1]);
            }
            if (op == REF) {
                return v.elements[(int) (long) k];
            }
            if (v.immutable) {
                throw new SchemeError("vector-set!: vector is immutable (a literal)");
            }
            v.elements[(int) (long) k] = args[2];
            return SConstant.UNSPECIFIED;
        }
    }
}
