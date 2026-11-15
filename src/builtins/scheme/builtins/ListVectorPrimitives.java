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
}
