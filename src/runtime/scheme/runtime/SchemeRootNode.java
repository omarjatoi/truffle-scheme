package scheme.runtime;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.ConstantOperand;
import com.oracle.truffle.api.bytecode.ContinuationRootNode;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.bytecode.StoreBytecodeIndex;
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
import com.oracle.truffle.api.source.SourceSection;

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
@GenerateBytecode(languageClass = SchemeLanguage.class, storeBytecodeIndexInFrame = true, enableTagInstrumentation = true, enableSerialization = true, //
                tagTreeNodeLibrary = SchemeTagTreeNodeExports.class)
public abstract class SchemeRootNode extends RootNode implements BytecodeRootNode {

    // Package-private: Bytecode DSL serialization saves these with the root (the prelude image,
    // design.md D13).

    /** The name of the variable the procedure is bound to, or null for an anonymous lambda. */
    String name;
    /**
     * The names of the captured variables by slot of {@link SClosure#captured}, null for one that
     * tools don't show (plan.md M6-05); null if none shows. Only {@link FrameVariables} reads it.
     */
    String[] capturedNames;
    /** The name of the fixed letrec* variable that is the running closure here, or null. */
    String selfName;

    protected SchemeRootNode(SchemeLanguage language, FrameDescriptor frameDescriptor) {
        super(language, frameDescriptor);
    }

    public void setName(String name) {
        this.name = name;
    }

    /** What the scope shows besides the locals (plan.md M6-05): see {@link FrameVariables}. */
    public void setVariableNames(String[] captured, String self) {
        this.capturedNames = captured;
        this.selfName = self;
    }

    /**
     * The name of the variable the procedure is bound to, or null: what {@code write} shows of it
     * ({@code #<procedure name>}) and its interop executable name.
     */
    public String getProcedureName() {
        return name;
    }

    /**
     * The name tools show (stack traces, the debugger, profilers): the procedure's name, or for an
     * anonymous lambda, where it is ({@code <lambda@file.scm:3:5>}).
     */
    @Override
    public String getName() {
        return name != null ? name : anonymousName();
    }

    @TruffleBoundary
    private String anonymousName() {
        SourceSection section = getSourceSection();
        if (section == null || !section.isAvailable()) {
            return "<lambda>";
        }
        return "<lambda@" + section.getSource().getName() + ":" + section.getStartLine() + ":" + section.getStartColumn() + ">";
    }

    @Override
    public String toString() {
        return getName();
    }

    // ------------------------------------------------------------------------------------------
    // Calls

    @TruffleBoundary
    static SchemeError notAProcedure(Object value) {
        return new SchemeError("not a procedure: " + scheme.runtime.printer.Printer.print(value, scheme.runtime.printer.Printer.Mode.WRITE));
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
    static Object raiseHere(SchemeError e, SchemeContext context, Trampoline trampoline) {
        Object raise = context.raise;
        if (!e.isRaisable() || !(raise instanceof SClosure closure)) {
            throw e;
        }
        return trampoline.run(trampoline.executeCall(closure, new Object[]{closure, e.getErrorObject()}));
    }

    /**
     * A non-tail call: calls the procedure, then runs the trampoline. Past {@link #SPILL_DEPTH}
     * nested calls it returns a spill capture instead of calling. It stores the bytecode index:
     * guest frames below it need this call's location (design.md D13).
     *
     * <p>The arguments start at index 1 of the variadic array, and index 0 is set to the
     * procedure: the array is the callee's frame arguments as it is, without a copy.
     *
     * <p>A primitive that only returns values ({@link PrimitiveSpec#returnsValue()}) runs in place
     * (plan.md M5-09): the site caches a node of its own for it, so there is no Truffle call and
     * each site has its own type profile. The cache is keyed on the call target, so a global
     * redefined to another procedure fails the guard. The primitive neither counts toward the spill
     * depth (it doesn't nest) nor is tortured (it can't capture), and its errors are raised here, as
     * from a primitive that was called (design.md D8).
     *
     * <p>Each site has its own {@link Trampoline}, an inline cache of the procedures its callees
     * tail-call (plan.md M5-10). The site reports polymorphism through it: once it has seen a second
     * procedure, Truffle may split the procedure it is in (map's loop, for instance) for each of its
     * callers, so that each copy's site sees fewer procedures and can inline them. (The Bytecode DSL
     * of Truffle 25.5 generates code that doesn't compile for {@code @ReportPolymorphism} on an
     * operation, so the cache initializers report instead.)
     */
    @Operation(storeBytecodeIndex = true)
    public static final class Call {
        @Specialization(guards = {"procedure.callTarget == cachedTarget", "primitive != null"}, limit = "4")
        static Object doPrimitive(SClosure procedure, @Variadic(startOffset = 1) Object[] arguments,
                        @Bind Node node,
                        @Shared @Cached Trampoline trampoline,
                        @Cached("trampoline.noteCallee(procedure.callTarget)") RootCallTarget cachedTarget,
                        @Cached("inPlace(cachedTarget)") PrimitiveSpec spec,
                        @Cached("createNode(spec)") PrimitiveNode primitive) {
            arguments[0] = procedure;
            try {
                spec.checkArity(arguments.length - 1);
                return inPlaceResult(primitive.invoke(arguments));
            } catch (SchemeError e) {
                return raiseHere(e, SchemeContext.get(node), trampoline);
            }
        }

        @Specialization(guards = "procedure.callTarget == cachedTarget", limit = "4")
        static Object doDirect(SClosure procedure, @Variadic(startOffset = 1) Object[] arguments,
                        @Bind Node node,
                        @Shared @Cached Trampoline trampoline,
                        @Cached("trampoline.noteCallee(procedure.callTarget)") RootCallTarget cachedTarget,
                        @Cached("create(cachedTarget)") DirectCallNode callNode) {
            arguments[0] = procedure;
            SchemeContext context = SchemeContext.get(node);
            if (++context.callDepth > SPILL_DEPTH) {
                context.callDepth--;
                return Capture.spill(arguments, context.getDynamicEnv());
            }
            try {
                return tortured(trampoline.run(callNode.call(arguments)), cachedTarget, context);
            } catch (SchemeError e) {
                return raiseHere(e, context, trampoline);
            } finally {
                context.callDepth--;
            }
        }

        @Specialization(replaces = "doDirect")
        static Object doIndirect(SClosure procedure, @Variadic(startOffset = 1) Object[] arguments,
                        @Bind Node node,
                        @Shared @Cached Trampoline trampoline,
                        @Cached("trampoline.megamorphic()") IndirectCallNode callNode) {
            arguments[0] = procedure;
            SchemeContext context = SchemeContext.get(node);
            if (++context.callDepth > SPILL_DEPTH) {
                context.callDepth--;
                return Capture.spill(arguments, context.getDynamicEnv());
            }
            try {
                return tortured(trampoline.run(callNode.call(procedure.callTarget, arguments)), procedure.callTarget, context);
            } catch (SchemeError e) {
                return raiseHere(e, context, trampoline);
            } finally {
                context.callDepth--;
            }
        }

        @Fallback
        static Object doNotAProcedure(Object procedure, @SuppressWarnings("unused") @Variadic(startOffset = 1) Object[] arguments) {
            throw notAProcedure(procedure);
        }

        static PrimitiveSpec inPlace(RootCallTarget target) {
            return PrimitiveRootNode.inPlace(target);
        }

        static PrimitiveNode createNode(PrimitiveSpec spec) {
            return PrimitiveRootNode.createNode(spec);
        }
    }

    /**
     * A tail call: returns a request to the caller's trampoline instead of calling. The arguments
     * start at index 1, as in {@link Call}, and the array becomes the callee's frame arguments.
     *
     * <p>A primitive that only returns values runs in place instead, as at a non-tail call site
     * (plan.md M5-09), and its value is this procedure's. That is safe because it is a leaf
     * (AGENTS.md rule 1): it runs in constant space, and this frame has nothing left to do. Its error
     * leaves this frame for the call site that called it, which raises it (design.md D8): the site
     * whose trampoline would otherwise have called the primitive. Only that specialization stores
     * the bytecode index, so the frame's location is this call's while the primitive runs.
     */
    @Operation(storeBytecodeIndex = false)
    public static final class MakeTailCall {
        @Specialization(guards = {"procedure.callTarget == cachedTarget", "primitive != null"}, limit = "4")
        @StoreBytecodeIndex
        static Object doPrimitive(SClosure procedure, @Variadic(startOffset = 1) Object[] arguments,
                        @Cached("procedure.callTarget") RootCallTarget cachedTarget,
                        @Cached("inPlace(cachedTarget)") PrimitiveSpec spec,
                        @Cached("createNode(spec)") PrimitiveNode primitive) {
            arguments[0] = procedure;
            spec.checkArity(arguments.length - 1);
            return inPlaceResult(primitive.invoke(arguments));
        }

        @Specialization
        static TailCall doMake(Object procedure, @Variadic(startOffset = 1) Object[] arguments) {
            arguments[0] = procedure;
            return new TailCall(procedure, arguments);
        }

        static PrimitiveSpec inPlace(RootCallTarget target) {
            return PrimitiveRootNode.inPlace(target);
        }

        static PrimitiveNode createNode(PrimitiveSpec spec) {
            return PrimitiveRootNode.createNode(spec);
        }
    }

    /** A primitive run in place returns a value: never a request for a trampoline or a prompt. */
    static Object inPlaceResult(Object result) {
        assert !(result instanceof TailCall) && !(result instanceof Capture);
        return result;
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

    /**
     * The value a suspended frame is resumed with: the value of its call. A {@link SchemeError}
     * instead is an uncaught error unwinding through the frame (plan.md M6-04, {@code PromptDriver}):
     * the frame throws it from its call site, so the error's guest stack trace has the frame. An
     * error is never a Scheme value, so no call returns one. The frame's location is its call's,
     * whose bytecode index the call stored.
     */
    @Operation(storeBytecodeIndex = false)
    public static final class Resumed {
        @Specialization
        static Object doUnwind(SchemeError error) {
            throw error;
        }

        @Fallback
        static Object doValue(Object value) {
            return value;
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

    /**
     * Sets {@code captured[index]} of a closure that was made before the letrec* variable it
     * captures was initialized ("Fixing Letrec", plan.md M5-05): only between making the closures
     * of a group of lambdas and running any Scheme code, so no code sees the slot change, and
     * {@link SClosure#captured} can stay compilation-final (design.md D2).
     */
    @Operation
    @ConstantOperand(type = int.class)
    public static final class PatchCaptured {
        @Specialization
        static void doPatch(int index, SClosure closure, Object value) {
            closure.captured[index] = value;
        }
    }

    /** Stores the bytecode index: its arity error names this location (design.md D13). */
    @Operation(storeBytecodeIndex = true)
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
    @Operation(storeBytecodeIndex = false)
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

    /**
     * A global read: a compile-time constant while the cell's assumption holds (design.md D7). It
     * doesn't store the bytecode index: an unbound-variable error is raised in the calling frame
     * (D8), whose Call stores its own.
     */
    @Operation(storeBytecodeIndex = false)
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
