package scheme.builtins;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;

import scheme.runtime.PrimitiveNode;
import scheme.runtime.PrimitiveSpec;
import scheme.runtime.PrimitiveSpec.Result;
import scheme.runtime.SchemeError;
import scheme.runtime.value.SBytevector;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SString;

/**
 * The bytevectors area (R7RS §6.9): its primitives, all leaves (AGENTS.md rule 1). One of the
 * fan-out areas (AGENTS.md "Areas"): add this area's primitives here and nowhere else.
 *
 * <p>
 * Every procedure of §6.9 is a leaf, so all of them are here and none is in the prelude. Optional
 * {@code start}/{@code end} arguments are checked as exact integers with
 * {@code 0 <= start <= end <= length}. Every constructor returns a fresh mutable bytevector;
 * literals are immutable, and mutating one is an error (design.md D1, D8). UTF-8 coding goes
 * through {@link TruffleString}, which validates the input.
 */
final class BytevectorPrimitives {

    private BytevectorPrimitives() {
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
        IS_BYTEVECTOR("bytevector?", 1, 1),
        BYTEVECTOR("bytevector", 0, -1, Result.NEW_OBJECT),
        MAKE_BYTEVECTOR("make-bytevector", 1, 2, Result.NEW_OBJECT),
        BYTEVECTOR_LENGTH("bytevector-length", 1, 1),
        BYTEVECTOR_U8_REF("bytevector-u8-ref", 2, 2),
        BYTEVECTOR_U8_SET("bytevector-u8-set!", 3, 3),
        BYTEVECTOR_COPY("bytevector-copy", 1, 3, Result.NEW_OBJECT),
        BYTEVECTOR_COPY_INTO("bytevector-copy!", 3, 5),
        BYTEVECTOR_APPEND("bytevector-append", 0, -1, Result.NEW_OBJECT),
        UTF8_TO_STRING("utf8->string", 1, 3, Result.NEW_OBJECT),
        STRING_TO_UTF8("string->utf8", 1, 3, Result.NEW_OBJECT);

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
                case IS_BYTEVECTOR:
                    return new TypePredicate(x -> x instanceof SBytevector);
                case BYTEVECTOR:
                    return new MakeBytevector();
                case MAKE_BYTEVECTOR:
                    return new MakeBytevectorOfLength();
                case BYTEVECTOR_LENGTH:
                    return new BytevectorLength();
                case BYTEVECTOR_U8_REF:
                    return new BytevectorRef();
                case BYTEVECTOR_U8_SET:
                    return new BytevectorSet();
                case BYTEVECTOR_COPY:
                    return new BytevectorCopy();
                case BYTEVECTOR_COPY_INTO:
                    return new BytevectorCopyInto();
                case BYTEVECTOR_APPEND:
                    return new BytevectorAppend();
                case UTF8_TO_STRING:
                    return new Utf8ToString();
                case STRING_TO_UTF8:
                    return new StringToUtf8();
            }
            throw new AssertionError(this);
        }
    }

    /** {@code (bytevector byte ...)}. */
    static final class MakeBytevector extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            byte[] bytes = new byte[args.length - 1];
            for (int i = 1; i < args.length; i++) {
                bytes[i - 1] = toByte("bytevector", i, args[i]);
            }
            return new SBytevector(bytes);
        }
    }

    /** {@code (make-bytevector k [byte])}: filled with 0 when {@code byte} is absent. */
    static final class MakeBytevectorOfLength extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            if (!(args[1] instanceof Long k) || k < 0 || k > Integer.MAX_VALUE - 8) {
                throw notA("make-bytevector", 1, "bytevector length (an exact integer from 0 to " + (Integer.MAX_VALUE - 8) + ")", args[1]);
            }
            byte[] bytes = new byte[(int) (long) k];
            if (args.length > 2) {
                java.util.Arrays.fill(bytes, toByte("make-bytevector", 2, args[2]));
            }
            return new SBytevector(bytes);
        }
    }

    static final class BytevectorLength extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return (long) bytevector("bytevector-length", 1, args[1]).bytes.length;
        }
    }

    /** {@code (bytevector-u8-ref bytevector k)}. */
    static final class BytevectorRef extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            SBytevector bv = bytevector("bytevector-u8-ref", 1, args[1]);
            return (long) (bv.bytes[index("bytevector-u8-ref", bv, args[2])] & 0xff);
        }
    }

    /** {@code (bytevector-u8-set! bytevector k byte)}. */
    static final class BytevectorSet extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            SBytevector bv = mutable("bytevector-u8-set!", 1, args[1]);
            int k = index("bytevector-u8-set!", bv, args[2]);
            bv.bytes[k] = toByte("bytevector-u8-set!", 3, args[3]);
            return SConstant.UNSPECIFIED;
        }
    }

    /** {@code (bytevector-copy bytevector [start [end]])}: a fresh mutable copy. */
    static final class BytevectorCopy extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            SBytevector bv = bytevector("bytevector-copy", 1, args[1]);
            int length = bv.bytes.length;
            int start = start("bytevector-copy", args, 2, length);
            int end = end("bytevector-copy", args, 3, start, length);
            return new SBytevector(java.util.Arrays.copyOfRange(bv.bytes, start, end));
        }
    }

    /**
     * {@code (bytevector-copy! to at from [start [end]])}. Source and destination may overlap
     * (R7RS: the copy behaves as if through a temporary), which {@code System.arraycopy} does.
     */
    static final class BytevectorCopyInto extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            String who = "bytevector-copy!";
            SBytevector to = mutable(who, 1, args[1]);
            SBytevector from = bytevector(who, 3, args[3]);
            int start = start(who, args, 4, from.bytes.length);
            int end = end(who, args, 5, start, from.bytes.length);
            int count = end - start;
            if (!(args[2] instanceof Long at) || at < 0 || at > to.bytes.length - count) {
                throw tooLong(to, args[2], count);
            }
            System.arraycopy(from.bytes, start, to.bytes, (int) (long) at, count);
            return SConstant.UNSPECIFIED;
        }

        @TruffleBoundary
        private static SchemeError tooLong(SBytevector to, Object at, int count) {
            return ControlPrimitives.notA("bytevector-copy!", 2, SchemeError.index(0, to.bytes.length - count) + ", at which the " + count + " bytes fit", at);
        }
    }

    /** {@code (bytevector-append bytevector ...)}. */
    static final class BytevectorAppend extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return append(args);
        }

        @TruffleBoundary
        private static SBytevector append(Object[] args) {
            long total = 0;
            for (int i = 1; i < args.length; i++) {
                total += bytevector("bytevector-append", i, args[i]).bytes.length;
            }
            if (total > Integer.MAX_VALUE - 8) {
                throw SchemeError.of("bytevector-append: the result is longer than the longest bytevector, " + (Integer.MAX_VALUE - 8) + " bytes");
            }
            byte[] bytes = new byte[(int) total];
            int at = 0;
            for (int i = 1; i < args.length; i++) {
                byte[] part = ((SBytevector) args[i]).bytes;
                System.arraycopy(part, 0, bytes, at, part.length);
                at += part.length;
            }
            return new SBytevector(bytes);
        }
    }

    /** {@code (utf8->string bytevector [start [end]])}: a fresh mutable string; invalid UTF-8 is an error. */
    static final class Utf8ToString extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            String who = "utf8->string";
            SBytevector bv = bytevector(who, 1, args[1]);
            int start = start(who, args, 2, bv.bytes.length);
            int end = end(who, args, 3, start, bv.bytes.length);
            return decode(bv, bv.bytes, start, end);
        }

        @TruffleBoundary
        private static SString decode(SBytevector bv, byte[] bytes, int start, int end) {
            TruffleString ts = TruffleString.fromByteArrayUncached(bytes, start, end - start, TruffleString.Encoding.UTF_8, true);
            if (!ts.isValidUncached(TruffleString.Encoding.UTF_8)) {
                throw notA("utf8->string", 1, "bytevector of UTF-8 (in the range)", bv);
            }
            return SString.of(ts.toJavaStringUncached());
        }
    }

    /** {@code (string->utf8 string [start [end]])}: {@code start} and {@code end} count characters. */
    static final class StringToUtf8 extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            String who = "string->utf8";
            if (!(args[1] instanceof SString s)) {
                throw notA(who, 1, "string", args[1]);
            }
            int start = start(who, args, 2, s.length());
            int end = end(who, args, 3, start, s.length());
            return new SBytevector(encode(s, start, end));
        }

        @TruffleBoundary
        private static byte[] encode(SString s, int start, int end) {
            StringBuilder sb = new StringBuilder(end - start);
            for (int i = start; i < end; i++) {
                sb.appendCodePoint(s.codePointAt(i));
            }
            TruffleString ts = TruffleString.fromJavaStringUncached(sb.toString(), TruffleString.Encoding.UTF_8);
            return ts.copyToByteArrayUncached(TruffleString.Encoding.UTF_8);
        }
    }

    // Shared checks. The fast paths stay in compiled code; building an error message is behind a boundary.

    // Each takes the procedure's name and the argument's position in its call (design.md D8, "Messages").

    private static SBytevector bytevector(String who, int position, Object value) {
        if (value instanceof SBytevector bv) {
            return bv;
        }
        throw notA(who, position, "bytevector", value);
    }

    private static SBytevector mutable(String who, int position, Object value) {
        SBytevector bv = bytevector(who, position, value);
        if (bv.immutable) {
            throw notA(who, position, "mutable bytevector (it is a literal)", bv);
        }
        return bv;
    }

    private static byte toByte(String who, int position, Object value) {
        if (value instanceof Long b && b >= 0 && b <= 255) {
            return (byte) (long) b;
        }
        throw notA(who, position, "byte (an exact integer from 0 to 255)", value);
    }

    /** The index, argument 2. */
    private static int index(String who, SBytevector bv, Object value) {
        if (value instanceof Long k && k >= 0 && k < bv.bytes.length) {
            return (int) (long) k;
        }
        throw badIndex(who, 2, value, 0, bv.bytes.length - 1);
    }

    /** The optional start argument at {@code args[i]}: 0 when absent. */
    private static int start(String who, Object[] args, int i, int length) {
        if (args.length <= i) {
            return 0;
        }
        if (args[i] instanceof Long k && k >= 0 && k <= length) {
            return (int) (long) k;
        }
        throw badIndex(who, i, args[i], 0, length);
    }

    /** The optional end argument at {@code args[i]}: {@code length} when absent. */
    private static int end(String who, Object[] args, int i, int start, int length) {
        if (args.length <= i) {
            return length;
        }
        if (args[i] instanceof Long k && k >= start && k <= length) {
            return (int) (long) k;
        }
        throw badIndex(who, i, args[i], start, length);
    }

    @TruffleBoundary
    private static SchemeError notA(String who, int position, String what, Object value) {
        return ControlPrimitives.notA(who, position, what, value);
    }

    /** Builds the message behind the boundary, so compiled code carries no string concatenation. */
    @TruffleBoundary
    private static SchemeError badIndex(String who, int position, Object value, long min, long max) {
        return SchemeError.argument(who, position, SchemeError.index(min, max), value);
    }
}
