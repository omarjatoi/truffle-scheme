package scheme.builtins;

import java.util.List;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;

import scheme.runtime.PrimitiveNode;
import scheme.runtime.PrimitiveSpec;
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

    /** This area's primitives: the one list to add to. */
    static List<PrimitiveSpec> primitives() {
        return List.of(
                        new PrimitiveSpec("bytevector?", 1, 1, () -> new TypePredicate(x -> x instanceof SBytevector)),
                        new PrimitiveSpec("bytevector", 0, -1, MakeBytevector::new),
                        new PrimitiveSpec("make-bytevector", 1, 2, MakeBytevectorOfLength::new),
                        new PrimitiveSpec("bytevector-length", 1, 1, BytevectorLength::new),
                        new PrimitiveSpec("bytevector-u8-ref", 2, 2, BytevectorRef::new),
                        new PrimitiveSpec("bytevector-u8-set!", 3, 3, BytevectorSet::new),
                        new PrimitiveSpec("bytevector-copy", 1, 3, BytevectorCopy::new),
                        new PrimitiveSpec("bytevector-copy!", 3, 5, BytevectorCopyInto::new),
                        new PrimitiveSpec("bytevector-append", 0, -1, BytevectorAppend::new),
                        new PrimitiveSpec("utf8->string", 1, 3, Utf8ToString::new),
                        new PrimitiveSpec("string->utf8", 1, 3, StringToUtf8::new));
    }

    /** {@code (bytevector byte ...)}. */
    static final class MakeBytevector extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            byte[] bytes = new byte[args.length];
            for (int i = 0; i < args.length; i++) {
                bytes[i] = toByte("bytevector", args[i]);
            }
            return new SBytevector(bytes);
        }
    }

    /** {@code (make-bytevector k [byte])}: filled with 0 when {@code byte} is absent. */
    static final class MakeBytevectorOfLength extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            if (!(args[0] instanceof Long k) || k < 0 || k > Integer.MAX_VALUE - 8) {
                throw notA("make-bytevector", "valid bytevector length", args[0]);
            }
            byte[] bytes = new byte[(int) (long) k];
            if (args.length > 1) {
                java.util.Arrays.fill(bytes, toByte("make-bytevector", args[1]));
            }
            return new SBytevector(bytes);
        }
    }

    static final class BytevectorLength extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return (long) bytevector("bytevector-length", args[0]).bytes.length;
        }
    }

    /** {@code (bytevector-u8-ref bytevector k)}. */
    static final class BytevectorRef extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            SBytevector bv = bytevector("bytevector-u8-ref", args[0]);
            return (long) (bv.bytes[index("bytevector-u8-ref", bv, args[1])] & 0xff);
        }
    }

    /** {@code (bytevector-u8-set! bytevector k byte)}. */
    static final class BytevectorSet extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            SBytevector bv = mutable("bytevector-u8-set!", args[0]);
            int k = index("bytevector-u8-set!", bv, args[1]);
            bv.bytes[k] = toByte("bytevector-u8-set!", args[2]);
            return SConstant.UNSPECIFIED;
        }
    }

    /** {@code (bytevector-copy bytevector [start [end]])}: a fresh mutable copy. */
    static final class BytevectorCopy extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            SBytevector bv = bytevector("bytevector-copy", args[0]);
            int length = bv.bytes.length;
            int start = start("bytevector-copy", args, 1, length);
            int end = end("bytevector-copy", args, 2, start, length);
            return new SBytevector(java.util.Arrays.copyOfRange(bv.bytes, start, end));
        }
    }

    /**
     * {@code (bytevector-copy! to at from [start [end]])}. Source and destination may overlap
     * (R7RS: the copy behaves as if through a temporary), which {@code System.arraycopy} does.
     */
    static final class BytevectorCopyInto extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            String who = "bytevector-copy!";
            SBytevector to = mutable(who, args[0]);
            SBytevector from = bytevector(who, args[2]);
            int start = start(who, args, 3, from.bytes.length);
            int end = end(who, args, 4, start, from.bytes.length);
            int count = end - start;
            if (!(args[1] instanceof Long at) || at < 0 || at > to.bytes.length - count) {
                throw badIndex(who, "destination index", to.bytes.length, args[1]);
            }
            System.arraycopy(from.bytes, start, to.bytes, (int) (long) at, count);
            return SConstant.UNSPECIFIED;
        }
    }

    /** {@code (bytevector-append bytevector ...)}. */
    static final class BytevectorAppend extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return append(args);
        }

        @TruffleBoundary
        private static SBytevector append(Object[] args) {
            long total = 0;
            for (Object arg : args) {
                total += bytevector("bytevector-append", arg).bytes.length;
            }
            if (total > Integer.MAX_VALUE - 8) {
                throw new SchemeError("bytevector-append: result too long");
            }
            byte[] bytes = new byte[(int) total];
            int at = 0;
            for (Object arg : args) {
                byte[] part = ((SBytevector) arg).bytes;
                System.arraycopy(part, 0, bytes, at, part.length);
                at += part.length;
            }
            return new SBytevector(bytes);
        }
    }

    /** {@code (utf8->string bytevector [start [end]])}: a fresh mutable string; invalid UTF-8 is an error. */
    static final class Utf8ToString extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            String who = "utf8->string";
            SBytevector bv = bytevector(who, args[0]);
            int start = start(who, args, 1, bv.bytes.length);
            int end = end(who, args, 2, start, bv.bytes.length);
            return decode(bv.bytes, start, end);
        }

        @TruffleBoundary
        private static SString decode(byte[] bytes, int start, int end) {
            TruffleString ts = TruffleString.fromByteArrayUncached(bytes, start, end - start, TruffleString.Encoding.UTF_8, true);
            if (!ts.isValidUncached(TruffleString.Encoding.UTF_8)) {
                throw new SchemeError("utf8->string: not valid UTF-8");
            }
            return SString.of(ts.toJavaStringUncached());
        }
    }

    /** {@code (string->utf8 string [start [end]])}: {@code start} and {@code end} count characters. */
    static final class StringToUtf8 extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            String who = "string->utf8";
            if (!(args[0] instanceof SString s)) {
                throw notA(who, "string", args[0]);
            }
            int start = start(who, args, 1, s.length());
            int end = end(who, args, 2, start, s.length());
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

    private static SBytevector bytevector(String who, Object value) {
        if (value instanceof SBytevector bv) {
            return bv;
        }
        throw notA(who, "bytevector", value);
    }

    private static SBytevector mutable(String who, Object value) {
        SBytevector bv = bytevector(who, value);
        if (bv.immutable) {
            throw immutable(who);
        }
        return bv;
    }

    private static byte toByte(String who, Object value) {
        if (value instanceof Long b && b >= 0 && b <= 255) {
            return (byte) (long) b;
        }
        throw notA(who, "byte (an exact integer 0-255)", value);
    }

    private static int index(String who, SBytevector bv, Object value) {
        if (value instanceof Long k && k >= 0 && k < bv.bytes.length) {
            return (int) (long) k;
        }
        throw badIndex(who, "index", bv.bytes.length, value);
    }

    /** The optional start argument at {@code args[i]}: 0 when absent. */
    private static int start(String who, Object[] args, int i, int length) {
        if (args.length <= i) {
            return 0;
        }
        if (args[i] instanceof Long k && k >= 0 && k <= length) {
            return (int) (long) k;
        }
        throw badIndex(who, "start index", length, args[i]);
    }

    /** The optional end argument at {@code args[i]}: {@code length} when absent. */
    private static int end(String who, Object[] args, int i, int start, int length) {
        if (args.length <= i) {
            return length;
        }
        if (args[i] instanceof Long k && k >= start && k <= length) {
            return (int) (long) k;
        }
        throw badIndex(who, "end index", length, args[i]);
    }

    @TruffleBoundary
    private static SchemeError notA(String who, String what, Object value) {
        return ControlPrimitives.notA(who, what, value);
    }

    /** Builds the message behind the boundary, so compiled code carries no string concatenation. */
    @TruffleBoundary
    private static SchemeError badIndex(String who, String what, int length, Object value) {
        return ControlPrimitives.notA(who, "valid " + what + " for length " + length, value);
    }

    @TruffleBoundary
    private static SchemeError immutable(String who) {
        return new SchemeError(who + ": bytevector is immutable (a literal)");
    }
}
