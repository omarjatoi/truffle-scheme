package scheme.spike;

import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.ContinuationRootNode;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.bytecode.Variadic;
import com.oracle.truffle.api.bytecode.Yield;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * M0-02 spike: the smallest Bytecode DSL interpreter that has the call-site protocol from design.md
 * "Continuations": call, then {@code IsCapture}, then the custom yield {@code SuspendFrame}. Block
 * scoping stays at its default (on).
 */
@GenerateBytecode(languageClass = SpikeLanguage.class)
public abstract class SpikeNode extends RootNode implements BytecodeRootNode {

    protected SpikeNode(SpikeLanguage language, FrameDescriptor frameDescriptor) {
        super(language, frameDescriptor);
    }

    /** Calls a spike function with the two arguments every spike function takes: (box, mode). */
    @Operation
    public static final class Call {
        @Specialization(guards = "fn == cachedFn", limit = "3")
        static Object doDirect(FnRef fn, Object box, Object mode,
                        @Cached("fn") FnRef cachedFn,
                        @Cached("create(cachedFn.target)") DirectCallNode callNode) {
            return callNode.call(box, mode);
        }

        @Specialization(replaces = "doDirect")
        static Object doIndirect(FnRef fn, Object box, Object mode,
                        @Cached IndirectCallNode callNode) {
            return callNode.call(fn.target, box, mode);
        }
    }

    /** M0-03: calls a function with any number of arguments (fib, tak). */
    @Operation
    public static final class CallN {
        @Specialization(guards = "fn == cachedFn", limit = "3")
        static Object doDirect(FnRef fn, @Variadic Object[] args,
                        @Cached("fn") FnRef cachedFn,
                        @Cached("create(cachedFn.target)") DirectCallNode callNode) {
            return callNode.call(args);
        }

        @Specialization(replaces = "doDirect")
        static Object doIndirect(FnRef fn, @Variadic Object[] args,
                        @Cached IndirectCallNode callNode) {
            return callNode.call(fn.target, args);
        }
    }

    @Operation
    public static final class Sub {
        @Specialization
        static long doLong(long left, long right) {
            return left - right;
        }
    }

    @Operation
    public static final class Lt {
        @Specialization
        static boolean doLong(long left, long right) {
            return left < right;
        }
    }

    /** Stands in for {@code call/cc}: returns a fresh {@link Capture} marker when asked to capture. */
    @Operation
    public static final class CallCC {
        @Specialization
        static Object doCallCC(Object mode) {
            return mode == Capture.REQUEST ? Capture.create() : mode;
        }
    }

    @Operation
    public static final class IsCapture {
        @Specialization
        static boolean doCheck(Object value) {
            return value instanceof Capture;
        }
    }

    /**
     * Adds this frame's segment to the capture and yields the capture to this root's caller. When a
     * segment is resumed, the resume value becomes the value of this operation.
     */
    @Yield
    public static final class SuspendFrame {
        @Specialization
        static Object doSuspend(Capture capture, @Bind ContinuationRootNode root, @Bind MaterializedFrame frame) {
            capture.addSegment(root, frame);
            return capture;
        }
    }

    @Operation
    public static final class Add {
        @Specialization
        static long doLong(long left, long right) {
            return left + right;
        }
    }

    @Operation
    public static final class Mul {
        @Specialization
        static long doLong(long left, long right) {
            return left * right;
        }
    }

    @Operation
    public static final class BoxGet {
        @Specialization
        static long doGet(Box box) {
            return box.value;
        }
    }

    @Operation
    public static final class BoxSet {
        @Specialization
        static void doSet(Box box, long value) {
            box.value = value;
        }
    }
}
