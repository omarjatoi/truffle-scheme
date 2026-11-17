package scheme.builtins;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.runtime.PrimitiveNode;
import scheme.runtime.PrimitiveSpec;
import scheme.runtime.PrimitiveSpec.Result;
import scheme.runtime.SchemeError;
import scheme.runtime.printer.Printer;
import scheme.runtime.value.LiteralPair;
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
        CONS("cons", 2, 2, Result.NEW_OBJECT),
        CAR("car", 1, 1),
        CDR("cdr", 1, 1),
        SET_CAR("set-car!", 2, 2),
        SET_CDR("set-cdr!", 2, 2),
        IS_NULL("null?", 1, 1),
        IS_PAIR("pair?", 1, 1),
        APPEND("append", 0, -1, Result.NEW_LIST),
        VECTOR("vector", 0, -1, Result.NEW_OBJECT),
        IS_VECTOR("vector?", 1, 1),
        MAKE_VECTOR("make-vector", 1, 2, Result.NEW_OBJECT),
        VECTOR_LENGTH("vector-length", 1, 1),
        VECTOR_REF("vector-ref", 2, 2),
        VECTOR_SET("vector-set!", 3, 3),
        LIST_TO_VECTOR("list->vector", 1, 1, Result.NEW_OBJECT),
        IS_LIST("list?", 1, 1),
        MAKE_LIST("make-list", 1, 2, Result.NEW_LIST),
        LIST_TAIL("list-tail", 2, 2),
        LIST_REF("list-ref", 2, 2),
        LIST_SET("list-set!", 3, 3),
        LIST_COPY("list-copy", 1, 1, Result.NEW_LIST),
        LENGTH("length", 1, 1),
        REVERSE("reverse", 1, 1, Result.NEW_LIST),
        MEMQ("memq", 2, 2),
        MEMV("memv", 2, 2),
        MEMBER("%member", 2, 2),
        ASSQ("assq", 2, 2),
        ASSV("assv", 2, 2),
        ASSOC("%assoc", 2, 2),
        IS_CIRCULAR_LIST("%circular-list?", 1, 1),
        VECTOR_TO_LIST("vector->list", 1, 3, Result.NEW_LIST),
        VECTOR_TO_STRING("vector->string", 1, 3, Result.NEW_OBJECT),
        STRING_TO_VECTOR("string->vector", 1, 3, Result.NEW_OBJECT),
        VECTOR_COPY("vector-copy", 1, 3, Result.NEW_OBJECT),
        VECTOR_COPY_INTO("vector-copy!", 3, 5),
        VECTOR_APPEND("vector-append", 0, -1, Result.NEW_OBJECT),
        VECTOR_FILL("vector-fill!", 2, 4);

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
                case CONS:
                    return new Cons();
                case CAR:
                    return new PairAccess("car", true);
                case CDR:
                    return new PairAccess("cdr", false);
                case SET_CAR:
                    return new SetPair(true);
                case SET_CDR:
                    return new SetPair(false);
                case IS_NULL:
                    return new TypePredicate(x -> x == SConstant.EMPTY_LIST);
                case IS_PAIR:
                    return new TypePredicate(x -> SPair.asPair(x) != null);
                case APPEND:
                    return new Append();
                case VECTOR:
                    return new MakeVector();
                case IS_VECTOR:
                    return new TypePredicate(x -> x instanceof SVector);
                case MAKE_VECTOR:
                    return new MakeVectorOfLength();
                case VECTOR_LENGTH:
                    return new VectorLength();
                case VECTOR_REF:
                    return new VectorRef();
                case VECTOR_SET:
                    return new VectorSet();
                case LIST_TO_VECTOR:
                    return new ListToVector();
                case IS_LIST:
                    return new IsList();
                case MAKE_LIST:
                    return new MakeList();
                case LIST_TAIL:
                    return new ListIndex(ListIndex.TAIL);
                case LIST_REF:
                    return new ListIndex(ListIndex.REF);
                case LIST_SET:
                    return new ListIndex(ListIndex.SET);
                case LIST_COPY:
                    return new ListCopy();
                case LENGTH:
                    return new Length();
                case REVERSE:
                    return new Reverse();
                case MEMQ:
                    return new Member("memq", Member.EQ, false);
                case MEMV:
                    return new Member("memv", Member.EQV, false);
                case MEMBER:
                    return new Member("member", Member.EQUAL, false);
                case ASSQ:
                    return new Member("assq", Member.EQ, true);
                case ASSV:
                    return new Member("assv", Member.EQV, true);
                case ASSOC:
                    return new Member("assoc", Member.EQUAL, true);
                case IS_CIRCULAR_LIST:
                    return new IsCircular();
                case VECTOR_TO_LIST:
                    return new VectorToList();
                case VECTOR_TO_STRING:
                    return new VectorToString();
                case STRING_TO_VECTOR:
                    return new StringToVector();
                case VECTOR_COPY:
                    return new VectorCopy();
                case VECTOR_COPY_INTO:
                    return new VectorCopyInto();
                case VECTOR_APPEND:
                    return new VectorAppend();
                case VECTOR_FILL:
                    return new VectorFill();
            }
            throw new AssertionError(this);
        }
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
            SPair p = SPair.asPair(args[1]);
            if (p != null) {
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
            // One class compare: a pair made at run time. A literal's pair (a LiteralPair) is an
            // error to store into (R7RS §3.4, design.md D1), as anything else is.
            // One class compare, which is also the type check: a pair made at run time. A
            // literal's pair is an error to store into (R7RS §3.4, design.md D1).
            Object x = args[1];
            if (SPair.isMutable(x)) {
                SPair p = SPair.asMutable(x);
                if (car) {
                    p.car = args[2];
                } else {
                    p.cdr = args[2];
                }
                return SConstant.UNSPECIFIED;
            }
            String who = car ? "set-car!" : "set-cdr!";
            throw x instanceof LiteralPair ? immutablePair(who) : Lists.notAPair(who, x);
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
            return SVector.of(java.util.Arrays.copyOfRange(args, 1, args.length), false);
        }
    }

    /** {@code (list->vector list)}. */
    static final class ListToVector extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return SVector.of(Lists.toArray("list->vector", args[1]), false);
        }
    }

    /** {@code (make-vector k [fill])}. */
    static final class MakeVectorOfLength extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            if (!(args[1] instanceof Long k) || k < 0 || k > Integer.MAX_VALUE - 8) {
                throw ControlPrimitives.notA("make-vector", "valid vector length", args[1]);
            }
            return SVector.filled((int) (long) k, args.length > 2 ? args[2] : SConstant.UNSPECIFIED);
        }
    }

    /** {@code (vector-length vector)}. */
    static final class VectorLength extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            if (args[1] instanceof SVector v) {
                return (long) v.length;
            }
            throw ControlPrimitives.notA("vector-length", "vector", args[1]);
        }
    }

    /** {@code (vector-ref vector k)}: specialized on the vector's storage ({@link VectorNodes}). */
    static final class VectorRef extends PrimitiveNode {
        @Child private VectorNodes.VectorRefNode ref = VectorNodesFactory.VectorRefNodeGen.create();

        @Override
        public Object invoke(Object[] args) {
            return ref.execute(args[1], args[2]);
        }
    }

    /** {@code (vector-set! vector k obj)}: specialized on the vector's storage ({@link VectorNodes}). */
    static final class VectorSet extends PrimitiveNode {
        @Child private VectorNodes.VectorSetNode set = VectorNodesFactory.VectorSetNodeGen.create();

        @Override
        public Object invoke(Object[] args) {
            return set.execute(args[1], args[2], args[3]);
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

    /** A literal pair's car or cdr stored into (R7RS §3.4, design.md D1). */
    @TruffleBoundary
    static SchemeError immutablePair(String who) {
        return new SchemeError(who + ": pair is immutable (a literal)");
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
                SPair p1 = SPair.asPair(fast);
                if (p1 == null) {
                    return fast == SConstant.EMPTY_LIST;
                }
                SPair p2 = SPair.asPair(p1.cdr);
                if (p2 == null) {
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
            SPair p = SPair.asPair(tail);
            if (p == null) {
                throw outOfRange(who, k, args[1]);
            }
            if (op == REF) {
                return p.car;
            }
            if (p instanceof LiteralPair) {
                throw immutablePair(who);
            }
            p.car = args[3];
            return SConstant.UNSPECIFIED;
        }

        @TruffleBoundary
        private static Object tail(String who, Object list, long k) {
            Object rest = list;
            for (long i = 0; i < k; i++) {
                SPair p = SPair.asPair(rest);
                if (p == null) {
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
     * {@code (length list)}. A circular list is not a list (R7RS §6.4), so it is an error: we raise
     * instead of counting forever (design.md D9). The cycle check is Floyd's, in the same loop: a
     * slow pointer, one pair per two steps, meets the walk only on a cycle.
     */
    static final class Length extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return length(args[1]);
        }

        @TruffleBoundary
        private static long length(Object list) {
            Object slow = list;
            Object rest = list;
            long n = 0;
            for (SPair p; (p = SPair.asPair(rest)) != null;) {
                rest = p.cdr;
                if ((++n & 1) == 0) {
                    slow = ((SPair) slow).cdr;
                    if (slow == rest) {
                        throw Lists.notAProperList("length", list);
                    }
                }
            }
            if (rest != SConstant.EMPTY_LIST) {
                throw Lists.notAProperList("length", list);
            }
            return n;
        }
    }

    /** {@code (reverse list)}: a fresh list; an improper or circular list is an error, as for length. */
    static final class Reverse extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return reverse(args[1]);
        }

        @TruffleBoundary
        private static Object reverse(Object list) {
            Object slow = list;
            Object rest = list;
            Object result = SConstant.EMPTY_LIST;
            long n = 0;
            for (SPair p; (p = SPair.asPair(rest)) != null;) {
                result = new SPair(p.car, result);
                rest = p.cdr;
                if ((++n & 1) == 0) {
                    slow = ((SPair) slow).cdr;
                    if (slow == rest) {
                        throw Lists.notAProperList("reverse", list);
                    }
                }
            }
            if (rest != SConstant.EMPTY_LIST) {
                throw Lists.notAProperList("reverse", list);
            }
            return result;
        }
    }

    /**
     * {@code (memq obj list)}, {@code memv}, and {@code member} without a predicate ({@code %member},
     * lists-vectors.scm); with {@code alist}, {@code assq}, {@code assv} and {@code assoc}'s
     * {@code %assoc}. The first sublist (or pair) whose element (or key) is the same as obj, or #f.
     * The argument is a list (R7RS §6.4), so one without obj that is improper or circular is an error
     * (design.md D9): raised at the tail, or, for a cycle, instead of searching forever (Floyd's cycle
     * check, as in {@link Length}).
     */
    static final class Member extends PrimitiveNode {
        static final int EQ = 0;
        static final int EQV = 1;
        static final int EQUAL = 2;

        private final String who;
        private final int compare;
        private final boolean alist;

        Member(String who, int compare, boolean alist) {
            this.who = who;
            this.compare = compare;
            this.alist = alist;
        }

        @Override
        public Object invoke(Object[] args) {
            return find(who, compare, alist, args[1], args[2]);
        }

        @TruffleBoundary
        private static Object find(String who, int compare, boolean alist, Object x, Object list) {
            Object slow = list;
            Object rest = list;
            long n = 0;
            for (SPair p; (p = SPair.asPair(rest)) != null;) {
                if (alist) {
                    SPair entry = SPair.asPair(p.car);
                    if (entry == null) {
                        throw Lists.notAPair(who, p.car);
                    }
                    if (same(compare, x, entry.car)) {
                        return entry;
                    }
                } else if (same(compare, x, p.car)) {
                    return p;
                }
                rest = p.cdr;
                if ((++n & 1) == 0) {
                    slow = ((SPair) slow).cdr;
                    if (slow == rest) {
                        throw Lists.notAProperList(who, list);
                    }
                }
            }
            if (rest != SConstant.EMPTY_LIST) {
                throw Lists.notAProperList(who, list);
            }
            return false;
        }

        private static boolean same(int compare, Object a, Object b) {
            switch (compare) {
                case EQ:
                    return ControlPrimitives.Eq.eq(a, b);
                case EQV:
                    return ControlPrimitives.Eqv.eqv(a, b);
                default:
                    return ControlPrimitives.Equal.equal(a, b);
            }
        }
    }

    /**
     * {@code (%circular-list? obj)}: whether obj is a circular list, one whose cdrs never end
     * (Floyd's cycle check). map and for-each (lists-vectors.scm) use it: R7RS §6.10 lets their lists
     * be circular as long as one is not.
     */
    static final class IsCircular extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return isCircular(args[1]);
        }

        @TruffleBoundary
        private static boolean isCircular(Object x) {
            Object slow = x;
            Object fast = x;
            for (SPair p1, p2; (p1 = SPair.asPair(fast)) != null && (p2 = SPair.asPair(p1.cdr)) != null;) {
                fast = p2.cdr;
                slow = ((SPair) slow).cdr;
                if (fast == slow) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * {@code (list-copy obj)}: fresh pairs for the spine of a list, proper or not, sharing the final
     * cdr; any other object is returned as is.
     */
    static final class ListCopy extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            SPair p = SPair.asPair(args[1]);
            return p != null ? copy(p) : args[1];
        }

        @TruffleBoundary
        private static Object copy(SPair list) {
            SPair head = new SPair(list.car, SConstant.EMPTY_LIST);
            SPair last = head;
            Object slow = list;
            Object rest = list.cdr;
            long n = 1;
            for (SPair p; (p = SPair.asPair(rest)) != null;) {
                SPair next = new SPair(p.car, SConstant.EMPTY_LIST);
                last.cdr = next;
                last = next;
                rest = p.cdr;
                if ((++n & 1) == 0) {
                    slow = ((SPair) slow).cdr;
                    if (slow == rest) {
                        throw Lists.notAProperList("list-copy", list);
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
            SVector v = vector("vector->list", args[1]);
            int start = start("vector->list", args, 2, v.length);
            int end = end("vector->list", args, 3, start, v.length);
            return toList(v, start, end);
        }

        @TruffleBoundary
        private static Object toList(SVector v, int start, int end) {
            Object result = SConstant.EMPTY_LIST;
            for (int i = end - 1; i >= start; i--) {
                result = new SPair(v.get(i), result);
            }
            return result;
        }
    }

    /** {@code (vector->string vector [start [end]])}: every element in the range must be a character. */
    static final class VectorToString extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            SVector v = vector("vector->string", args[1]);
            int start = start("vector->string", args, 2, v.length);
            int end = end("vector->string", args, 3, start, v.length);
            return toString(v, start, end);
        }

        @TruffleBoundary
        private static SString toString(SVector v, int start, int end) {
            SString result = SString.make(end - start, 0);
            for (int i = start; i < end; i++) {
                Object element = v.get(i);
                if (!(element instanceof SChar c)) {
                    throw ControlPrimitives.notA("vector->string", "character", element);
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
            SVector v = vector("vector-copy", args[1]);
            int start = start("vector-copy", args, 2, v.length);
            int end = end("vector-copy", args, 3, start, v.length);
            return v.copy(start, end);
        }
    }

    /** {@code (vector-copy! to at from [start [end]])}: correct when the ranges overlap. */
    static final class VectorCopyInto extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            SVector to = mutableVector("vector-copy!", args[1]);
            int at = index("vector-copy!", args[2], to.length);
            SVector from = vector("vector-copy!", args[3]);
            int start = start("vector-copy!", args, 4, from.length);
            int end = end("vector-copy!", args, 5, start, from.length);
            if (end - start > to.length - at) {
                throw tooLong(end - start, at, to.length);
            }
            to.copyFrom(at, from, start, end);
            return SConstant.UNSPECIFIED;
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
                total += vector("vector-append", args[i]).length;
            }
            if (total > Integer.MAX_VALUE - 8) {
                throw new SchemeError("vector-append: result too long");
            }
            // The first vector's kind of storage, generalized by the first element that doesn't fit it.
            SVector result = args.length == 1 ? new SVector(new Object[0], false) : ((SVector) args[1]).blank((int) total);
            int at = 0;
            for (int i = 1; i < args.length; i++) {
                SVector v = (SVector) args[i];
                result.copyFrom(at, v, 0, v.length);
                at += v.length;
            }
            return result;
        }
    }

    /** {@code (vector-fill! vector fill [start [end]])}. */
    static final class VectorFill extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            SVector v = mutableVector("vector-fill!", args[1]);
            int start = start("vector-fill!", args, 3, v.length);
            int end = end("vector-fill!", args, 4, start, v.length);
            v.fill(start, end, args[2]);
            return SConstant.UNSPECIFIED;
        }
    }
}
