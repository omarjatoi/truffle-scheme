package scheme.builtins;

import java.util.Arrays;
import java.util.List;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.runtime.PrimitiveNode;
import scheme.runtime.PrimitiveSpec;
import scheme.runtime.SchemeError;
import scheme.runtime.printer.Printer;
import scheme.runtime.value.SChar;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SString;
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
                        new PrimitiveSpec("list->vector", 1, 1, ListToVector::new),
                        new PrimitiveSpec("list?", 1, 1, IsList::new),
                        new PrimitiveSpec("make-list", 1, 2, MakeList::new),
                        new PrimitiveSpec("list-tail", 2, 2, () -> new ListIndex(ListIndex.TAIL)),
                        new PrimitiveSpec("list-ref", 2, 2, () -> new ListIndex(ListIndex.REF)),
                        new PrimitiveSpec("list-set!", 3, 3, () -> new ListIndex(ListIndex.SET)),
                        new PrimitiveSpec("list-copy", 1, 1, ListCopy::new),
                        new PrimitiveSpec("vector->list", 1, 3, VectorToList::new),
                        new PrimitiveSpec("vector->string", 1, 3, VectorToString::new),
                        new PrimitiveSpec("string->vector", 1, 3, StringToVector::new),
                        new PrimitiveSpec("vector-copy", 1, 3, VectorCopy::new),
                        new PrimitiveSpec("vector-copy!", 3, 5, VectorCopyInto::new),
                        new PrimitiveSpec("vector-append", 0, -1, VectorAppend::new),
                        new PrimitiveSpec("vector-fill!", 2, 4, VectorFill::new));
    }

    static final class Cons extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return new SPair(args[1], args[2]);
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
        public Object invoke(Object[] args) {
            if (args[1] instanceof SPair p) {
                return car ? p.car : p.cdr;
            }
            throw notAPair(name, args[1]);
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
        public Object invoke(Object[] args) {
            if (!(args[1] instanceof SPair p)) {
                throw Lists.notAPair(car ? "set-car!" : "set-cdr!", args[1]);
            }
            if (car) {
                p.car = args[2];
            } else {
                p.cdr = args[2];
            }
            return SConstant.UNSPECIFIED;
        }
    }

    /** {@code (append list ... obj)}: copies every list but the last, which is shared. */
    static final class Append extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return append(args);
        }

        @TruffleBoundary
        private static Object append(Object[] args) {
            if (args.length == 1) {
                return SConstant.EMPTY_LIST;
            }
            Object result = args[args.length - 1];
            for (int i = args.length - 2; i >= 1; i--) {
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
        public Object invoke(Object[] args) {
            return new SVector(java.util.Arrays.copyOfRange(args, 1, args.length), false);
        }
    }

    /** {@code (list->vector list)}. */
    static final class ListToVector extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return new SVector(Lists.toArray("list->vector", args[1]), false);
        }
    }

    /** {@code (make-vector k [fill])}. */
    static final class MakeVectorOfLength extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            if (!(args[1] instanceof Long k) || k < 0 || k > Integer.MAX_VALUE - 8) {
                throw ControlPrimitives.notA("make-vector", "valid vector length", args[1]);
            }
            Object[] elements = new Object[(int) (long) k];
            java.util.Arrays.fill(elements, args.length > 2 ? args[2] : SConstant.UNSPECIFIED);
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
        public Object invoke(Object[] args) {
            String name = op == LENGTH ? "vector-length" : op == REF ? "vector-ref" : "vector-set!";
            if (!(args[1] instanceof SVector v)) {
                throw ControlPrimitives.notA(name, "vector", args[1]);
            }
            if (op == LENGTH) {
                return (long) v.elements.length;
            }
            if (!(args[2] instanceof Long k) || k < 0 || k >= v.elements.length) {
                throw ControlPrimitives.notA(name, "valid index for a vector of length " + v.elements.length, args[2]);
            }
            if (op == REF) {
                return v.elements[(int) (long) k];
            }
            if (v.immutable) {
                throw new SchemeError("vector-set!: vector is immutable (a literal)");
            }
            v.elements[(int) (long) k] = args[3];
            return SConstant.UNSPECIFIED;
        }
    }

    /** An index argument: an exact integer in {@code [0, bound]}. */
    static int index(String who, Object k, int bound) {
        if (k instanceof Long i && i >= 0 && i <= bound) {
            return (int) (long) i;
        }
        throw badIndex(who, k, bound);
    }

    @TruffleBoundary
    private static SchemeError badIndex(String who, Object k, int bound) {
        return ControlPrimitives.notA(who, "valid index (0 to " + bound + ")", k);
    }

    /** The optional {@code start} at {@code args[i]}: an index in {@code [0, length]}, 0 when absent. */
    static int start(String who, Object[] args, int i, int length) {
        return args.length > i ? index(who, args[i], length) : 0;
    }

    /** The optional {@code end} at {@code args[i]}: an index in {@code [start, length]}, length when absent. */
    static int end(String who, Object[] args, int i, int start, int length) {
        if (args.length <= i) {
            return length;
        }
        int end = index(who, args[i], length);
        if (end < start) {
            throw badEnd(who, end, start);
        }
        return end;
    }

    @TruffleBoundary
    private static SchemeError badEnd(String who, int end, int start) {
        return new SchemeError(who + ": end " + end + " is before start " + start);
    }

    static SVector vector(String who, Object x) {
        if (x instanceof SVector v) {
            return v;
        }
        throw ControlPrimitives.notA(who, "vector", x);
    }

    static SVector mutableVector(String who, Object x) {
        SVector v = vector(who, x);
        if (v.immutable) {
            throw immutable(who);
        }
        return v;
    }

    @TruffleBoundary
    private static SchemeError immutable(String who) {
        return new SchemeError(who + ": vector is immutable (a literal)");
    }

    /** {@code (list? obj)}: #t for a proper list, #f for an improper or circular one (tortoise and hare, D9). */
    static final class IsList extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return isList(args[1]);
        }

        @TruffleBoundary
        static boolean isList(Object x) {
            Object slow = x;
            Object fast = x;
            while (true) {
                if (!(fast instanceof SPair p1)) {
                    return fast == SConstant.EMPTY_LIST;
                }
                if (!(p1.cdr instanceof SPair p2)) {
                    return p1.cdr == SConstant.EMPTY_LIST;
                }
                fast = p2.cdr;
                slow = ((SPair) slow).cdr;
                if (fast == slow) {
                    return false;
                }
            }
        }
    }

    /** {@code (make-list k [fill])}. */
    static final class MakeList extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            if (!(args[1] instanceof Long k) || k < 0 || k > Integer.MAX_VALUE) {
                throw ControlPrimitives.notA("make-list", "valid list length", args[1]);
            }
            return make((int) (long) k, args.length > 2 ? args[2] : SConstant.UNSPECIFIED);
        }

        @TruffleBoundary
        private static Object make(int k, Object fill) {
            Object result = SConstant.EMPTY_LIST;
            for (int i = 0; i < k; i++) {
                result = new SPair(fill, result);
            }
            return result;
        }
    }

    /** {@code (list-tail list k)}, {@code (list-ref list k)} and {@code (list-set! list k obj)}. */
    static final class ListIndex extends PrimitiveNode {
        static final int TAIL = 0;
        static final int REF = 1;
        static final int SET = 2;

        private final int op;

        ListIndex(int op) {
            this.op = op;
        }

        @Override
        public Object invoke(Object[] args) {
            String who = op == TAIL ? "list-tail" : op == REF ? "list-ref" : "list-set!";
            if (!(args[2] instanceof Long k) || k < 0) {
                throw ControlPrimitives.notA(who, "valid list index", args[2]);
            }
            Object tail = tail(who, args[1], k);
            if (op == TAIL) {
                return tail;
            }
            if (!(tail instanceof SPair p)) {
                throw outOfRange(who, k, args[1]);
            }
            if (op == REF) {
                return p.car;
            }
            p.car = args[3];
            return SConstant.UNSPECIFIED;
        }

        @TruffleBoundary
        private static Object tail(String who, Object list, long k) {
            Object rest = list;
            for (long i = 0; i < k; i++) {
                if (!(rest instanceof SPair p)) {
                    throw outOfRange(who, k, list);
                }
                rest = p.cdr;
            }
            return rest;
        }

        @TruffleBoundary
        private static SchemeError outOfRange(String who, long k, Object list) {
            return new SchemeError(who + ": index " + k + " out of range for " + Printer.print(list, Printer.Mode.WRITE));
        }
    }

    /**
     * {@code (list-copy obj)}: fresh pairs for the spine of a list, proper or not, sharing the final
     * cdr; any other object is returned as is.
     */
    static final class ListCopy extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return args[1] instanceof SPair p ? copy(p) : args[1];
        }

        @TruffleBoundary
        private static Object copy(SPair list) {
            SPair head = new SPair(list.car, SConstant.EMPTY_LIST);
            SPair last = head;
            Object slow = list;
            Object rest = list.cdr;
            long n = 1;
            while (rest instanceof SPair p) {
                SPair next = new SPair(p.car, SConstant.EMPTY_LIST);
                last.cdr = next;
                last = next;
                rest = p.cdr;
                if ((++n & 1) == 0) {
                    slow = ((SPair) slow).cdr;
                    if (slow == rest) {
                        throw new SchemeError("list-copy: circular list");
                    }
                }
            }
            last.cdr = rest;
            return head;
        }
    }

    /** {@code (vector->list vector [start [end]])}. */
    static final class VectorToList extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            Object[] elements = vector("vector->list", args[1]).elements;
            int start = start("vector->list", args, 2, elements.length);
            int end = end("vector->list", args, 3, start, elements.length);
            return toList(elements, start, end);
        }

        @TruffleBoundary
        private static Object toList(Object[] elements, int start, int end) {
            Object result = SConstant.EMPTY_LIST;
            for (int i = end - 1; i >= start; i--) {
                result = new SPair(elements[i], result);
            }
            return result;
        }
    }

    /** {@code (vector->string vector [start [end]])}: every element in the range must be a character. */
    static final class VectorToString extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            Object[] elements = vector("vector->string", args[1]).elements;
            int start = start("vector->string", args, 2, elements.length);
            int end = end("vector->string", args, 3, start, elements.length);
            return toString(elements, start, end);
        }

        @TruffleBoundary
        private static SString toString(Object[] elements, int start, int end) {
            SString result = SString.make(end - start, 0);
            for (int i = start; i < end; i++) {
                if (!(elements[i] instanceof SChar c)) {
                    throw ControlPrimitives.notA("vector->string", "character", elements[i]);
                }
                result.set(i - start, c.codePoint);
            }
            return result;
        }
    }

    /** {@code (string->vector string [start [end]])}. */
    static final class StringToVector extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            if (!(args[1] instanceof SString s)) {
                throw ControlPrimitives.notA("string->vector", "string", args[1]);
            }
            int start = start("string->vector", args, 2, s.length());
            int end = end("string->vector", args, 3, start, s.length());
            return toVector(s, start, end);
        }

        @TruffleBoundary
        private static SVector toVector(SString s, int start, int end) {
            Object[] elements = new Object[end - start];
            for (int i = start; i < end; i++) {
                elements[i - start] = SChar.of(s.codePointAt(i));
            }
            return new SVector(elements, false);
        }
    }

    /** {@code (vector-copy vector [start [end]])}: a new mutable vector. */
    static final class VectorCopy extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            Object[] elements = vector("vector-copy", args[1]).elements;
            int start = start("vector-copy", args, 2, elements.length);
            int end = end("vector-copy", args, 3, start, elements.length);
            return new SVector(copy(elements, start, end), false);
        }

        @TruffleBoundary
        private static Object[] copy(Object[] elements, int start, int end) {
            return Arrays.copyOfRange(elements, start, end);
        }
    }

    /** {@code (vector-copy! to at from [start [end]])}: correct when the ranges overlap. */
    static final class VectorCopyInto extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            Object[] to = mutableVector("vector-copy!", args[1]).elements;
            int at = index("vector-copy!", args[2], to.length);
            Object[] from = vector("vector-copy!", args[3]).elements;
            int start = start("vector-copy!", args, 4, from.length);
            int end = end("vector-copy!", args, 5, start, from.length);
            if (end - start > to.length - at) {
                throw tooLong(end - start, at, to.length);
            }
            copy(from, start, to, at, end - start);
            return SConstant.UNSPECIFIED;
        }

        @TruffleBoundary
        private static void copy(Object[] from, int start, Object[] to, int at, int count) {
            System.arraycopy(from, start, to, at, count);
        }

        @TruffleBoundary
        private static SchemeError tooLong(int count, int at, int length) {
            return new SchemeError("vector-copy!: " + count + " elements don't fit at index " + at + " of a vector of length " + length);
        }
    }

    /** {@code (vector-append vector ...)}: a new mutable vector. */
    static final class VectorAppend extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return append(args);
        }

        @TruffleBoundary
        private static SVector append(Object[] args) {
            long total = 0;
            for (int i = 1; i < args.length; i++) {
                total += vector("vector-append", args[i]).elements.length;
            }
            if (total > Integer.MAX_VALUE - 8) {
                throw new SchemeError("vector-append: result too long");
            }
            Object[] result = new Object[(int) total];
            int at = 0;
            for (int i = 1; i < args.length; i++) {
                Object[] elements = ((SVector) args[i]).elements;
                System.arraycopy(elements, 0, result, at, elements.length);
                at += elements.length;
            }
            return new SVector(result, false);
        }
    }

    /** {@code (vector-fill! vector fill [start [end]])}. */
    static final class VectorFill extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            Object[] elements = mutableVector("vector-fill!", args[1]).elements;
            int start = start("vector-fill!", args, 3, elements.length);
            int end = end("vector-fill!", args, 4, start, elements.length);
            fill(elements, start, end, args[2]);
            return SConstant.UNSPECIFIED;
        }

        @TruffleBoundary
        private static void fill(Object[] elements, int start, int end, Object fill) {
            Arrays.fill(elements, start, end, fill);
        }
    }
}
