package scheme.runtime;

import java.util.Arrays;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.ConstantOperand;
import com.oracle.truffle.api.bytecode.ContinuationRootNode;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.bytecode.LocalRangeAccessor;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.bytecode.OperationProxy;
import com.oracle.truffle.api.bytecode.ShortCircuitOperation;
import com.oracle.truffle.api.bytecode.ShortCircuitOperation.Operator;
import com.oracle.truffle.api.bytecode.StoreBytecodeIndex;
import com.oracle.truffle.api.bytecode.Variadic;
import com.oracle.truffle.api.bytecode.Yield;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;
import com.oracle.truffle.api.source.SourceSection;

import scheme.runtime.value.SClosure;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SPair;

/**
 * The Bytecode DSL root of every Scheme procedure and program (design.md "Calls and tail calls").
 * Frame argument 0 is the procedure itself; the arguments follow.
 *
 * <p>The compiler (scheme.compiler) emits these operations. Every non-tail call site comes from its
 * CallSiteEmitter (AGENTS.md rule 2): {@code CallSite(Call(...), Resumed(SuspendFrame()))}, the
 * call's value or, for a capture, the value the suspended frame is resumed with.
 *
 * <p>A call of one of the hottest primitives is an operation of its own instead ({@link Intrinsics},
 * plan.md M7-03), and boxing elimination keeps fixnums, flonums and booleans unboxed between
 * operations and in locals.
 */
@GenerateBytecode(languageClass = SchemeLanguage.class, storeBytecodeIndexInFrame = true, enableTagInstrumentation = true, enableSerialization = true, //
                boxingEliminationTypes = {long.class, double.class, boolean.class}, tagTreeNodeLibrary = SchemeTagTreeNodeExports.class)
@OperationProxy(value = Intrinsics.Car.class, name = "Car")
@OperationProxy(value = Intrinsics.Cdr.class, name = "Cdr")
@OperationProxy(value = Intrinsics.Cons.class, name = "Cons")
@OperationProxy(value = Intrinsics.IsNull.class, name = "IsNull")
@OperationProxy(value = Intrinsics.IsPair.class, name = "IsPair")
@OperationProxy(value = Intrinsics.Eq.class, name = "Eq")
@OperationProxy(value = Intrinsics.Assoc.class, name = "Assoc")
@OperationProxy(value = Intrinsics.Not.class, name = "Not")
@OperationProxy(value = Intrinsics.Add.class, name = "Add")
@OperationProxy(value = Intrinsics.Sub.class, name = "Sub")
@OperationProxy(value = Intrinsics.Mul.class, name = "Mul")
@OperationProxy(value = Intrinsics.Div.class, name = "Div")
@OperationProxy(value = Intrinsics.IntegerDivide.class, name = "IntegerDivide")
@OperationProxy(value = Intrinsics.Compare.class, name = "Compare")
@OperationProxy(value = Intrinsics.IsZero.class, name = "IsZero")
@OperationProxy(value = Intrinsics.VectorRef.class, name = "VectorRef")
@OperationProxy(value = Intrinsics.VectorSet.class, name = "VectorSet")
@OperationProxy(value = Intrinsics.VectorLength.class, name = "VectorLength")
@ShortCircuitOperation(name = "CallSite", operator = Operator.OR_RETURN_VALUE, booleanConverter = SchemeRootNode.IsValue.class, //
                javadoc = "A non-tail call site (CallSiteEmitter): Call's value, unless it is a Capture; then the frame suspends.")
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
    /**
     * The top level the procedure was compiled in when it isn't the interaction environment: an
     * isolated program's (design.md D15) or a library's (D7), where code a debugger evaluates in its
     * frames resolves global names (plan.md M7-07). Opaque here: the compiler's. Transient, so
     * serialization leaves it out: the prelude image's roots have none, and their frames evaluate in
     * the interaction environment.
     */
    transient Object topLevel;

    protected SchemeRootNode(SchemeLanguage language, FrameDescriptor frameDescriptor) {
        super(language, frameDescriptor);
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * An {@link Intrinsics} operation raised an error in this frame, and {@code raise} came back with
     * a capture ({@link AbandonedFrame}): the root returns the capture, as a call site that suspends
     * would, but without a segment of this frame, which nothing can resume. Any other control-flow
     * exception (an escape, a continuation's invocation) goes on.
     */
    @Override
    public Object interceptControlFlowException(ControlFlowException ex, VirtualFrame frame, BytecodeNode bytecodeNode, int bci) throws Throwable {
        if (ex instanceof AbandonedFrame abandoned) {
            return abandoned.capture;
        }
        throw ex;
    }

    /** See {@link #topLevel}. */
    public void setTopLevel(Object topLevel) {
        this.topLevel = topLevel;
    }

    /** See {@link #topLevel}. */
    public Object getTopLevel() {
        return topLevel;
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
     * Torture mode (M2-06, AGENTS.md rules 2 and 3): after every non-tail call of a Scheme procedure
     * returns, capture up to the root prompt and resume from copies. A constant, so it costs nothing
     * when off. Primitive calls are not tortured: they can't capture anything.
     */
    static final boolean TORTURE = Boolean.getBoolean("scheme.torture");

    /**
     * Owned torture ({@code -Dscheme.torture.owned=true} with {@link #TORTURE}): the frames a
     * torture capture suspends are owned by the root prompt's driver, as a spill's are, so it
     * resumes them in place, once, until something shares them (design.md "Owned segments"). Every
     * non-tail call then exercises the in-place resume, and every continuation the copy (with its
     * check) of the segments it shares. Plain torture shares every capture, so that every resume
     * is checked from copies.
     */
    static final boolean TORTURE_OWNED = TORTURE && Boolean.getBoolean("scheme.torture.owned");

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
     * A non-tail call: calls the procedure, then runs the trampoline. When the nested calls would use
     * more Java stack than the thread has to spare ({@link StackBudget}), it returns a spill capture
     * instead of calling: that captures up to the root prompt, which makes the call on a fresh Java
     * stack (design.md "Continuations"). It stores the bytecode index: guest frames below it need
     * this call's location (design.md D13).
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
     * tail-call (plan.md M5-10). The site reports polymorphism through it when it goes megamorphic:
     * then Truffle may split the procedure it is in (map's loop, for instance) for each of its
     * callers, so that each copy's site sees fewer procedures and can inline them. Up to the cache
     * limit, the site inlines every procedure it has seen, and doesn't report (plan.md M7-01,
     * {@link Trampoline}). (The Bytecode DSL of Truffle 25.5 generates code that doesn't compile for
     * {@code @ReportPolymorphism} on an operation, so the cache initializer reports instead: a Truffle
     * bug, reproduced by {@code scheme.test.upstream.UpstreamReportPolymorphismTest},
     * docs/upstream.md#reportpolymorphism-on-an-operation. When that test fails after a Truffle
     * upgrade, put {@code @ReportPolymorphism.Megamorphic} here instead.)
     */
    @Operation(storeBytecodeIndex = true)
    public static final class Call {
        @Specialization(guards = {"procedure.callTarget == cachedTarget", "primitive != null"}, limit = "4")
        static Object doPrimitive(SClosure procedure, @Variadic(startOffset = 1) Object[] arguments,
                        @Bind Node node,
                        @Shared @Cached Trampoline trampoline,
                        @Cached("procedure.callTarget") RootCallTarget cachedTarget,
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
                        @Cached("procedure.callTarget") RootCallTarget cachedTarget,
                        @Cached("create(cachedTarget)") DirectCallNode callNode,
                        @Bind SchemeRootNode caller,
                        @Bind VirtualFrame frame,
                        @Shared @Cached InlinedBranchProfile checked,
                        @Shared @Cached InlinedBranchProfile spilled) {
            arguments[0] = procedure;
            SchemeContext context = SchemeContext.get(node);
            int use = context.stackUse;
            int nested = use + StackBudget.callCost(context, caller.getFrameDescriptor().getNumberOfSlots());
            if (StackBudget.checks(context, nested) && spills(node, checked, spilled, use, nested, context)) {
                return Capture.spill(arguments, context.getDynamicEnv());
            }
            context.stackUse = nested;
            handOffArguments(context, arguments);
            try {
                return tortured(trampoline.run(frame, callNode.call(arguments)), cachedTarget, context);
            } catch (SchemeError e) {
                return raiseHere(e, context, trampoline);
            } finally {
                context.stackUse = use;
            }
        }

        @Specialization(replaces = "doDirect")
        static Object doIndirect(SClosure procedure, @Variadic(startOffset = 1) Object[] arguments,
                        @Bind Node node,
                        @Shared @Cached Trampoline trampoline,
                        @Cached("trampoline.megamorphic()") IndirectCallNode callNode,
                        @Bind SchemeRootNode caller,
                        @Bind VirtualFrame frame,
                        @Shared @Cached InlinedBranchProfile checked,
                        @Shared @Cached InlinedBranchProfile spilled) {
            arguments[0] = procedure;
            SchemeContext context = SchemeContext.get(node);
            int use = context.stackUse;
            int nested = use + StackBudget.callCost(context, caller.getFrameDescriptor().getNumberOfSlots());
            if (StackBudget.checks(context, nested) && spills(node, checked, spilled, use, nested, context)) {
                return Capture.spill(arguments, context.getDynamicEnv());
            }
            context.stackUse = nested;
            handOffArguments(context, arguments);
            try {
                return tortured(trampoline.run(frame, callNode.call(procedure.callTarget, arguments)), procedure.callTarget, context);
            } catch (SchemeError e) {
                return raiseHere(e, context, trampoline);
            } finally {
                context.stackUse = use;
            }
        }

        @Fallback
        static Object doNotAProcedure(Object procedure, @SuppressWarnings("unused") @Variadic(startOffset = 1) Object[] arguments) {
            throw notAProcedure(procedure);
        }

        /**
         * The call would take the stack use past the limit, or, in a native image, past half of
         * it ({@link StackBudget#checks}): whether it spills (returns a spill capture instead of
         * calling). Within the limit, only if the call is one that checks the stack's real depth
         * ({@link StackBudget#crosses}) and the stack below it is deeper than the limit
         * ({@link StackBudget#pastLimit}). Past it, unless this is the first time on the thread and
         * the thread's stack, once measured, has room ({@link StackBudget#extend}), and the stack
         * below isn't deeper than the new limit. The check is behind a branch profile
         * ({@code checked}), so that compiled code of a site that never checks has none of it, and a
         * spill behind another (plan.md M7-01): most call sites never spill, and in compiled code the
         * capture (with the argument array it materializes) was most of the code of every non-tail
         * call site, so every compilation was about three times the size, and compilation fell
         * behind.
         */
        private static boolean spills(Node node, InlinedBranchProfile checked, InlinedBranchProfile spilled, int use, int nested, SchemeContext context) {
            checked.enter(node);
            if (nested <= context.stackLimit ? !StackBudget.crosses(use, nested) || !StackBudget.pastLimit(context)
                            : StackBudget.extend(context, nested) && !StackBudget.pastLimit(context)) {
                return false;
            }
            spilled.enter(node);
            return true;
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

    /**
     * The boolean converter of {@code CallSite}: whether a call's result is a value, not a
     * {@link Capture}. A capture is handed to the {@link SuspendFrame} that runs next, through the
     * context ({@link SchemeContext#suspending}): the short circuit drops the operand it converted.
     * No Scheme code runs in between. A site that never sees a capture has only the first
     * specialization's type check, which return-type profiling folds in compiled code (M0-03). (An
     * {@code @Operation} only because the Truffle 25.5 DSL processor fails on a converter that
     * isn't one.)
     */
    @Operation(storeBytecodeIndex = false)
    public static final class IsValue {
        @Specialization
        static boolean doCapture(Capture capture, @Bind Node node) {
            SchemeContext.get(node).suspending = capture;
            return false;
        }

        @Fallback
        static boolean doValue(@SuppressWarnings("unused") Object value) {
            return true;
        }
    }

    /**
     * Suspends this frame into the capture that {@link IsValue} has just handed over, and passes the
     * capture up (design.md "Continuations").
     */
    @Yield
    public static final class SuspendFrame {
        @Specialization
        static Object doSuspend(@Bind ContinuationRootNode root, @Bind MaterializedFrame frame, @Bind Node node) {
            SchemeContext context = SchemeContext.get(node);
            Capture capture = context.suspending;
            context.suspending = null;
            capture.addSegment(root, frame);
            return capture;
        }
    }

    /**
     * The value a suspended frame is resumed with: the value of its call. A {@link SchemeError}
     * instead is an uncaught error unwinding through the frame (plan.md M6-04, {@code PromptDriver}):
     * the frame throws it from its call site, so the error's guest stack trace has the frame. An
     * error is never a Scheme value, so no call returns one. The frame's location is its call's,
     * whose bytecode index the call stored. A prompt's driver passes the value in a
     * {@link Resumption}, which this empties.
     */
    @Operation(storeBytecodeIndex = false)
    public static final class Resumed {
        @Specialization
        static Object doResume(Resumption resumption) {
            return resumption.take();
        }

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

    /** Makes no calls: binding the node (to report the allocation) needs no bytecode index stored. */
    @Operation(storeBytecodeIndex = false)
    @ConstantOperand(type = SchemeRootNode.class)
    public static final class MakeClosure {
        @Specialization
        static SClosure doMake(SchemeRootNode root, @Variadic Object[] captured, @Bind Node node) {
            SClosure closure = new SClosure(root.getCallTarget(), captured);
            Allocations.report(node, closure, Allocations.OBJECT);
            return closure;
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

    /**
     * The prologue of a procedure whose parameters are all required (plan.md M8-04): checks the
     * number of arguments, as {@link CheckArity} does, and stores each argument in its parameter's
     * local, in a {@link Box} if the parameter is assigned (design.md D2): one instruction on every
     * call, where {@code CheckArity} and a load and a store per parameter were 1 + 2n.
     * {@code boxed} has bit {@code i} set if parameter {@code i} is boxed. Each store goes through
     * the local's boxing-elimination profile, as a {@code StoreLocal} would, so a parameter that is
     * always a fixnum stays an unboxed {@code long}. Stores the bytecode index: its arity error names
     * this location (design.md D13).
     *
     * <p>It clears each argument once bound, and releases the caller's array
     * ({@link #releaseArguments}): the caller's Java frames (a call site, a trampoline, a prompt's
     * driver) may hold that until this procedure returns, and a tail loop in it may run for ever
     * (R7RS §3.5, design.md "Tail calls keep nothing alive"). Nothing reads an argument but the
     * closure after the prologue: tools read the parameters' locals.
     */
    @Operation(storeBytecodeIndex = true)
    @ConstantOperand(type = LocalRangeAccessor.class)
    @ConstantOperand(type = int.class)
    public static final class BindArguments {
        @Specialization
        @ExplodeLoop
        static void doBind(LocalRangeAccessor parameters, int boxed, @Bind VirtualFrame frame, @Bind BytecodeNode bytecode) {
            Object[] arguments = frame.getArguments();
            int n = parameters.getLength();
            if (arguments.length - 1 != n) {
                throw arityError(n, n, arguments.length - 1);
            }
            for (int i = 0; i < n; i++) {
                Object value = arguments[i + 1];
                parameters.setObject(bytecode, frame, i, (boxed & (1 << i)) != 0 ? new Box(value) : value);
                arguments[i + 1] = null;
            }
            if (CompilerDirectives.inCompiledCode()) {
                releaseArguments(SchemeContext.get(bytecode), arguments);
            }
        }
    }

    /**
     * The end of the prologue of a procedure that doesn't start with {@link BindArguments} (a rest
     * parameter, or more than 31 parameters): it clears the arguments, which are bound by now, and
     * releases the caller's array, as {@code BindArguments} does.
     */
    @Operation(storeBytecodeIndex = false)
    public static final class ReleaseArguments {
        @Specialization
        static void doRelease(@Bind VirtualFrame frame, @Bind Node node) {
            Object[] arguments = frame.getArguments();
            if (CompilerDirectives.inCompiledCode()) {
                releaseArguments(SchemeContext.get(node), arguments);
            }
            Arrays.fill(arguments, 1, arguments.length, null);
        }
    }

    /**
     * Hands {@code arguments} over to the callee's prologue ({@link #releaseArguments}), from a
     * call site or a trampoline about to call with them. Only from the interpreter: a compiled
     * caller's frame doesn't keep what it no longer uses, and storing the array would make it escape
     * where it can otherwise stay virtual (on {@code fib}, the store made every call allocate its
     * array: 2.4 times slower).
     */
    static void handOffArguments(SchemeContext context, Object[] arguments) {
        if (CompilerDirectives.inInterpreter()) {
            context.callArguments = arguments;
        }
    }

    /**
     * From compiled code: clears the caller's argument array of the call that is running
     * ({@link SchemeContext#callArguments}) but its procedure, if {@code arguments} were copied
     * from it. The arguments are bound, and nothing reads them again. The prologue clears
     * {@code arguments} itself, which in the interpreter is the caller's array: so only compiled
     * code releases. A call whose array no compiled prologue releases leaves a stale one, which the
     * next call replaces; one that isn't this call's is left alone (clearing a stale one is
     * harmless: its call is past its prologue).
     *
     * <p>Only in the root of a compilation: only an interpreted caller hands an array over, and its
     * call enters the callee's compiled code at its root. A prologue inlined into compiled code was
     * called from compiled code, whose arguments are its own, and its check of the hand-off would
     * be a load and a branch on every inlined call that the compiler can't remove: it made
     * {@code quicksort} 1.36 times and {@code triangl} 1.21 times slower (plan.md M9-02).
     */
    static void releaseArguments(SchemeContext context, Object[] arguments) {
        if (!CompilerDirectives.inCompilationRoot()) {
            return;
        }
        Object[] original = context.callArguments;
        if (original != null) {
            context.callArguments = null;
            if (original != arguments && original.length == arguments.length && original[0] == arguments[0]) {
                Arrays.fill(original, 1, original.length, null);
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
        static Object doRest(int start, @Bind VirtualFrame frame, @Bind Node node) {
            Object[] arguments = frame.getArguments();
            Object list = SConstant.EMPTY_LIST;
            for (int i = arguments.length - 1; i >= start; i--) {
                list = new SPair(arguments[i], list);
            }
            Allocations.report(node, list, Allocations.LIST);
            return list;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Variables

    /**
     * {@code value}, once {@code local} is cleared: the last operand of a non-tail call in tail
     * position of the let that binds {@code local} (design.md "Tail calls keep nothing alive"). The
     * call replaces the let's frame (R7RS §3.5), so nothing reads the variable after it, and the
     * value must not stay reachable while the call runs.
     */
    @Operation(storeBytecodeIndex = false)
    @ConstantOperand(type = LocalAccessor.class)
    public static final class ThenClear {
        @Specialization
        static Object doClear(LocalAccessor local, Object value, @Bind VirtualFrame frame, @Bind BytecodeNode bytecode) {
            local.clear(bytecode, frame);
            return value;
        }
    }

    /**
     * The condition of the {@code While} a self tail call or a contified loop loops in: true.
     * {@code argumentCount} is the length of the root's argument array, or -1 if it varies (a rest
     * parameter).
     *
     * <p>On-stack replacement enters the compiled loop here, with a copy of the interpreter's frame,
     * and Truffle keeps the original until the loop is done: its locals (the loop's variables when
     * it was entered) would keep what they refer to reachable for as long as the loop runs, which
     * a loop over a lazily built list can't afford (R7RS §3.5). The OSR frame's arguments end with
     * that frame ({@code BytecodeOSRNode#storeParentFrameInArguments}), so the first iteration
     * clears it and drops it from the arguments: Truffle restores it from the OSR frame when the
     * loop is done. A call's arguments are never a frame. With a materialized frame, OSR
     * runs in it, and there is no copy.
     */
    @Operation(storeBytecodeIndex = false)
    @ConstantOperand(type = int.class)
    public static final class LoopHead {
        @Specialization
        static boolean doHead(int argumentCount, @Bind VirtualFrame frame) {
            releaseOSRParent(argumentCount, frame);
            return true;
        }

        /** Clears and drops the frame on-stack replacement started from, if this is the first iteration of an OSR-compiled loop. */
        static void releaseOSRParent(int argumentCount, VirtualFrame frame) {
            Object[] arguments = frame.getArguments();
            int last = arguments.length - 1;
            if ((argumentCount < 0 ? last >= 0 : last == argumentCount) && arguments[last] instanceof MaterializedFrame parent) {
                arguments[last] = null;
                clearOSRParent(parent);
            }
        }

        @TruffleBoundary
        private static void clearOSRParent(MaterializedFrame parent) {
            int slots = parent.getFrameDescriptor().getNumberOfSlots();
            for (int i = 0; i < slots; i++) {
                if (parent.getTag(i) == FrameSlotKind.Object.tag) {
                    parent.clear(i);
                }
            }
        }
    }

    /**
     * The condition of the {@code While} of a loop whose body is an {@code if} with the loop's jumps
     * in one branch only (plan.md M8-04): the {@code if}'s test, so the loop goes on while it takes
     * that branch ({@code loopsWhen} is the test's truth for it), and leaves for the other, which
     * follows the loop. It replaces {@link LoopHead} and the {@code if}'s own branch: two
     * instructions fewer on every iteration and every call of a self-tail-calling procedure. It
     * releases the frame on-stack replacement started from, as {@code LoopHead} does.
     */
    @Operation(storeBytecodeIndex = false)
    @ConstantOperand(type = int.class)
    @ConstantOperand(type = boolean.class)
    public static final class LoopTest {
        @Specialization
        static boolean doBoolean(int argumentCount, boolean loopsWhen, boolean test, @Bind VirtualFrame frame) {
            LoopHead.releaseOSRParent(argumentCount, frame);
            return test == loopsWhen;
        }

        /** Anything but a boolean is true (Scheme truth, as {@link Truthy}). */
        @Fallback
        static boolean doOther(int argumentCount, boolean loopsWhen, @SuppressWarnings("unused") Object test, @Bind VirtualFrame frame) {
            LoopHead.releaseOSRParent(argumentCount, frame);
            return loopsWhen;
        }
    }

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
