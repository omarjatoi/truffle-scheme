package scheme.runtime;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.ConstantOperand;
import com.oracle.truffle.api.bytecode.ContinuationRootNode;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.bytecode.Variadic;
import com.oracle.truffle.api.bytecode.Yield;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;

import scheme.runtime.value.SClosure;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SPair;

/**
 * The Bytecode DSL root of every Scheme procedure and program (design.md "Calls and tail calls").
 * Frame argument 0 is the procedure itself; the arguments follow.
 *
 * <p>The compiler (scheme.compiler) emits these operations. Every non-tail call site comes from its
 * CallSiteEmitter (AGENTS.md rule 2): {@code Call}, then {@code IsCapture}, then
 * {@code SuspendFrame}.
 */
@GenerateBytecode(languageClass = SchemeLanguage.class)
public abstract class SchemeRootNode extends RootNode implements BytecodeRootNode {

    private String name;

    protected SchemeRootNode(SchemeLanguage language, FrameDescriptor frameDescriptor) {
        super(language, frameDescriptor);
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return name == null ? "<lambda>" : name;
    }

    // ------------------------------------------------------------------------------------------
    // Calls

    static Object[] withProcedure(Object procedure, Object[] arguments) {
        Object[] all = new Object[arguments.length + 1];
        all[0] = procedure;
        System.arraycopy(arguments, 0, all, 1, arguments.length);
        return all;
    }

    @TruffleBoundary
    static SchemeError notAProcedure(Object value) {
        return new SchemeError("not a procedure: " + scheme.runtime.printer.Printer.print(value, scheme.runtime.printer.Printer.Mode.WRITE));
    }

    /** Performs tail calls until a value comes back. */
    static Object trampoline(Object first, IndirectCallNode callNode) {
        Object result = first;
        while (result instanceof TailCall tail) {
            if (!(tail.procedure instanceof SClosure closure)) {
                throw notAProcedure(tail.procedure);
            }
            result = callNode.call(closure.callTarget, tail.arguments);
        }
        return result;
    }

    /**
     * The number of nested non-tail calls after which a call spills instead (design.md
     * "Continuations"): it captures up to the root prompt, which makes the call on a fresh Java stack.
     * The interpreter fits 64 nested calls in a 256k Java stack, but not 80 (M2-05), so 50 leaves a
     * margin. Override with {@code -Dscheme.spillDepth}.
     */
    static final int SPILL_DEPTH = Integer.getInteger("scheme.spillDepth", 50);

    /**
     * Torture mode (M2-06, AGENTS.md rules 2 and 3): after every non-tail call of a Scheme procedure
     * returns, capture up to the root prompt and resume from copies. A constant, so it costs nothing
     * when off. Primitive calls are not tortured: they can't capture anything.
     */
    static final boolean TORTURE = Boolean.getBoolean("scheme.torture");

    static Object tortured(Object result, RootCallTarget callee, SchemeContext context) {
        if (TORTURE && !(result instanceof Capture) && !(callee.getRootNode() instanceof PrimitiveRootNode)) {
            return Capture.torture(result, context.getDynamicEnv());
        }
        return result;
    }

    /**
     * An error from the callee (a primitive, an arity or unbound-variable check, the trampoline) is
     * raised in this frame (design.md D8): this call's value is what calling Scheme {@code raise}
     * with the error object returns, so a capture in a handler (call/cc, guard) suspends this frame
     * like any other call. An uncaught error, or one before the prelude has installed raise, goes on.
     */
    static Object raiseHere(SchemeError e, SchemeContext context, IndirectCallNode callNode) {
        Object raise = context.raise;
        if (!e.isRaisable() || !(raise instanceof SClosure closure)) {
            throw e;
        }
        return trampoline(callNode.call(closure.callTarget, new Object[]{closure, e.getErrorObject()}), callNode);
    }

    /**
     * A non-tail call: calls the procedure, then runs the trampoline. Past {@link #SPILL_DEPTH}
     * nested calls it returns a spill capture instead of calling.
     */
    @Operation
    public static final class Call {
        @Specialization(guards = "procedure.callTarget == cachedTarget", limit = "4")
        static Object doDirect(SClosure procedure, @Variadic Object[] arguments,
                        @Bind Node node,
                        @Cached("procedure.callTarget") RootCallTarget cachedTarget,
                        @Cached("create(cachedTarget)") DirectCallNode callNode,
                        @Shared @Cached IndirectCallNode trampolineNode) {
            SchemeContext context = SchemeContext.get(node);
            if (++context.callDepth > SPILL_DEPTH) {
                context.callDepth--;
                return Capture.spill(withProcedure(procedure, arguments), context.getDynamicEnv());
            }
            try {
                return tortured(trampoline(callNode.call(withProcedure(procedure, arguments)), trampolineNode), cachedTarget, context);
            } catch (SchemeError e) {
                return raiseHere(e, context, trampolineNode);
            } finally {
                context.callDepth--;
            }
        }

        @Specialization(replaces = "doDirect")
        static Object doIndirect(SClosure procedure, @Variadic Object[] arguments,
                        @Bind Node node,
                        @Shared @Cached IndirectCallNode trampolineNode) {
            SchemeContext context = SchemeContext.get(node);
            if (++context.callDepth > SPILL_DEPTH) {
                context.callDepth--;
                return Capture.spill(withProcedure(procedure, arguments), context.getDynamicEnv());
            }
            try {
                return tortured(trampoline(trampolineNode.call(procedure.callTarget, withProcedure(procedure, arguments)), trampolineNode), procedure.callTarget, context);
            } catch (SchemeError e) {
                return raiseHere(e, context, trampolineNode);
            } finally {
                context.callDepth--;
            }
        }

        @Fallback
        static Object doNotAProcedure(Object procedure, @SuppressWarnings("unused") @Variadic Object[] arguments) {
            throw notAProcedure(procedure);
        }
    }

    /** A tail call: returns a request to the caller's trampoline instead of calling. */
    @Operation
    public static final class MakeTailCall {
        @Specialization
        static TailCall doMake(Object procedure, @Variadic Object[] arguments) {
            return new TailCall(procedure, withProcedure(procedure, arguments));
        }
    }

    /** Identity test, used to turn a self tail call into a loop. */
    @Operation
    public static final class IsSame {
        @Specialization
        static boolean doCheck(Object a, Object b) {
            return a == b;
        }
    }

    @Operation
    public static final class IsCapture {
        @Specialization
        static boolean doCheck(Object value) {
            return value instanceof Capture;
        }
    }

    /** Suspends this frame into the capture and passes the capture up (design.md "Continuations"). */
    @Yield
    public static final class SuspendFrame {
        @Specialization
        static Object doSuspend(Capture capture, @Bind ContinuationRootNode root, @Bind MaterializedFrame frame) {
            capture.addSegment(root, frame);
            return capture;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Procedures and arguments

    @Operation
    @ConstantOperand(type = SchemeRootNode.class)
    public static final class MakeClosure {
        @Specialization
        static SClosure doMake(SchemeRootNode root, @Variadic Object[] captured) {
            return new SClosure(root.getCallTarget(), captured);
        }
    }

    /** {@code captured[index]} of the running closure (frame argument 0). */
    @Operation
    @ConstantOperand(type = int.class)
    public static final class LoadCaptured {
        @Specialization
        static Object doLoad(int index, SClosure closure) {
            return closure.captured[index];
        }
    }

    @Operation
    @ConstantOperand(type = int.class)
    @ConstantOperand(type = int.class)
    public static final class CheckArity {
        @Specialization
        static void doCheck(int required, int max, @Bind VirtualFrame frame) {
            int count = frame.getArguments().length - 1;
            if (count < required || (max >= 0 && count > max)) {
                throw arityError(required, max, count);
            }
        }
    }

    @TruffleBoundary
    static SchemeError arityError(int required, int max, int count) {
        String expected = max < 0 ? "at least " + required : Integer.toString(required);
        return new SchemeError("wrong number of arguments: expected " + expected + ", got " + count);
    }

    /** The arguments from {@code start} on, as a list (a rest parameter). */
    @Operation
    @ConstantOperand(type = int.class)
    public static final class RestList {
        @Specialization
        static Object doRest(int start, @Bind VirtualFrame frame) {
            Object[] arguments = frame.getArguments();
            Object list = SConstant.EMPTY_LIST;
            for (int i = arguments.length - 1; i >= start; i--) {
                list = new SPair(arguments[i], list);
            }
            return list;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Variables

    @Operation
    public static final class MakeBox {
        @Specialization
        static Box doMake(Object value) {
            return new Box(value);
        }
    }

    @Operation
    public static final class BoxRef {
        @Specialization
        static Object doRef(Box box) {
            return box.value;
        }
    }

    @Operation
    public static final class BoxSet {
        @Specialization
        static void doSet(Box box, Object value) {
            box.value = value;
        }
    }

    /** A global read: a compile-time constant while the cell's assumption holds (design.md D7). */
    @Operation
    @ConstantOperand(type = GlobalCell.class)
    public static final class GlobalRef {
        @Specialization(assumptions = "stable", guards = "value != null")
        static Object doStable(@SuppressWarnings("unused") GlobalCell cell,
                        @SuppressWarnings("unused") @Cached(value = "cell.getStable()", neverDefault = true) Assumption stable,
                        @Cached(value = "cell.valueOrNull()", neverDefault = false) Object value) {
            return value;
        }

        @Specialization(replaces = "doStable")
        static Object doRead(GlobalCell cell) {
            return cell.read();
        }
    }

    @Operation
    @ConstantOperand(type = GlobalCell.class)
    public static final class GlobalSet {
        @Specialization
        static void doSet(GlobalCell cell, Object value) {
            cell.set(value);
        }
    }

    @Operation
    @ConstantOperand(type = GlobalCell.class)
    public static final class GlobalDefine {
        @Specialization
        static void doDefine(GlobalCell cell, Object value) {
            cell.define(value);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Control

    /** Scheme truth: everything except #f is true. */
    @Operation
    public static final class Truthy {
        @Specialization
        static boolean doBoolean(boolean value) {
            return value;
        }

        @Fallback
        static boolean doOther(@SuppressWarnings("unused") Object value) {
            return true;
        }
    }
}
