package scheme.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.instrumentation.AllocationReporter;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.Node;

import scheme.runtime.value.MultipleValues;
import scheme.runtime.value.SBigInteger;
import scheme.runtime.value.SBytevector;
import scheme.runtime.value.SChar;
import scheme.runtime.value.SClosure;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SRecord;
import scheme.runtime.value.SString;
import scheme.runtime.value.SSymbol;
import scheme.runtime.value.SVector;

/**
 * Reports the Scheme objects the language allocates to allocation listeners, such as the memory
 * tracer ({@code --memtracer}), through the context's {@link AllocationReporter} (plan.md M7-07):
 * closures and rest-argument lists where the bytecode makes them, and the results of the
 * primitives that make new objects ({@link PrimitiveSpec#allocates}: pairs, vectors, strings,
 * bytevectors, records, bignums, ...). Boxes (design.md D2) aren't reported: they are not interop
 * values, which is all an allocation listener takes.
 *
 * <p>Until a listener is active, nothing is reported, at no cost in compiled code: a report first
 * checks the language's {@link SchemeLanguage#noAllocationListeners} assumption, which the first
 * active listener of any of its contexts invalidates. An object is reported once it exists, so
 * {@code onEnter} and {@code onReturnValue} follow each other, both with its estimated size.
 */
public final class Allocations {

    /** {@link PrimitiveSpec#allocates}: the result is never reported. */
    public static final int NONE = 0;
    /** The result, when it is a Scheme heap object, is a new one. */
    public static final int OBJECT = 1;
    /** The result, when it is a list, has new pairs: its size is its pairs'. */
    public static final int LIST = 2;

    /** Object header and reference sizes on a 64-bit JVM with compressed pointers, rounded up. */
    private static final long HEADER = 16;
    private static final long REFERENCE = 8;

    private Allocations() {
    }

    /** Reports {@code value}, made at {@code node}, as {@code kind} ({@link #OBJECT} or {@link #LIST}), if a listener is active. */
    public static void report(Node node, Object value, int kind) {
        if (!SchemeLanguage.get(node).noAllocationListeners.isValid()) {
            reportNow(node, value, kind);
        }
    }

    @TruffleBoundary
    private static void reportNow(Node node, Object value, int kind) {
        AllocationReporter reporter = SchemeContext.get(node).allocationReporter;
        if (!reporter.isActive() || !isReported(value)) {
            return;
        }
        long size = kind == LIST && value instanceof SPair ? listSize(value) : sizeOf(value);
        reporter.onEnter(null, 0, size);
        reporter.onReturnValue(value, 0, size);
    }

    /** Scheme heap objects; not the constants, symbols (interned) or characters, nor host values. */
    private static boolean isReported(Object value) {
        return value instanceof TruffleObject && !(value instanceof SConstant || value instanceof SSymbol || value instanceof SChar || value instanceof MultipleValues);
    }

    /** A list's pairs, up to its end or a cycle's start (only the first pairs of a cyclic one count). */
    private static long listSize(Object list) {
        long pairs = 0;
        for (Object p = list; p instanceof SPair pair && pairs < Integer.MAX_VALUE; p = pair.cdr) {
            pairs++;
            if (pair.cdr == list) {
                break;
            }
        }
        return pairs * sizeOf(list);
    }

    /** An estimate of the object's size in bytes, with its own arrays. */
    static long sizeOf(Object value) {
        if (value instanceof SPair) {
            return HEADER + 2 * REFERENCE;
        } else if (value instanceof SVector vector) {
            return HEADER + REFERENCE + 8 + array(vector.length * 8L);
        } else if (value instanceof SString string) {
            return HEADER + 2 * REFERENCE + 8 + array(string.isLatin1() ? string.length() : string.length() * 4L);
        } else if (value instanceof SBytevector bytevector) {
            return HEADER + REFERENCE + 8 + array(bytevector.bytes.length);
        } else if (value instanceof SClosure closure) {
            return HEADER + 2 * REFERENCE + array(closure.captured.length * REFERENCE);
        } else if (value instanceof SRecord record) {
            return HEADER + REFERENCE + record.type.fieldNames.length * REFERENCE;
        } else if (value instanceof SBigInteger big) {
            // The BigInteger and its int[] magnitude.
            return HEADER + REFERENCE + 2 * HEADER + array((big.value.bitLength() + 31) / 32 * 4L);
        }
        return 2 * HEADER;
    }

    private static long array(long bytes) {
        return HEADER + (bytes + 7) / 8 * 8;
    }

    /**
     * The node of a primitive that allocates ({@link PrimitiveSpec#allocates}): the primitive's
     * own, then a report of its result.
     */
    static final class Reporting extends PrimitiveNode {
        @Child private PrimitiveNode body;
        private final int kind;

        Reporting(PrimitiveNode body, int kind) {
            this.body = body;
            this.kind = kind;
        }

        @Override
        public Object invoke(Object[] args) {
            Object result = body.invoke(args);
            report(this, result, kind);
            return result;
        }
    }
}
