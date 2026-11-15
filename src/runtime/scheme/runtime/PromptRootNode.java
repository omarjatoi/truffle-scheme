package scheme.runtime;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.bytecode.ContinuationRootNode;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;

import scheme.runtime.printer.Printer;
import scheme.runtime.value.SClosure;

/**
 * The prompt around a program (design.md D6, "Continuations"). It runs the program, and then:
 *
 * <ul>
 * <li>a {@link Capture} that reaches it is completed into a {@link Continuation} (the capture's
 * segments followed by the segments still pending) and its receiver is called inside it;</li>
 * <li>a value is passed to the innermost pending segment, which is resumed from a fresh copy of its
 * frame, never the original;</li>
 * <li>a {@link TailCall} (a resumed segment may end in one) is performed;</li>
 * <li>an {@link InvokeContinuation} replaces the pending segments with the invoked continuation's,
 * and restores its dynamic environment (rewind with winders is M2-04).</li>
 * </ul>
 *
 * Every resume starts on this Java frame, so re-entry costs O(1) Java stack depth. Hand-written: it
 * is a driver loop, not Scheme code.
 */
public final class PromptRootNode extends RootNode {

    private static final Object[] NO_ARGUMENTS = new Object[0];

    @Child private DirectCallNode body;
    @Child private IndirectCallNode callNode = IndirectCallNode.create();
    @Child private ResumeNode resumeNode = PromptRootNodeFactory.ResumeNodeGen.create();
    private final SchemeLanguage language;

    public PromptRootNode(SchemeLanguage language, CallTarget body) {
        super(language);
        this.language = language;
        this.body = DirectCallNode.create(body);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        SchemeContext context = SchemeContext.get(this);
        SegmentList pending = null;
        Object value;
        try {
            value = body.call(NO_ARGUMENTS);
        } catch (InvokeContinuation e) {
            pending = e.continuation.segments;
            context.setDynamicEnv(e.continuation.dynEnv);
            value = e.value;
        }
        while (true) {
            try {
                if (value instanceof Capture capture) {
                    Continuation k = new Continuation(capture.segmentsBefore(pending), capture.dynEnv);
                    pending = k.segments;
                    value = call(capture.receiver, new Object[]{language.continuationProcedure(k)});
                } else if (value instanceof TailCall tail) {
                    value = callWithProcedure(tail.procedure, tail.arguments);
                } else if (pending == null) {
                    return value;
                } else {
                    Capture.Segment segment = pending.head;
                    pending = pending.tail;
                    value = resumeNode.execute(segment.root(), copy(segment.frame()), value);
                }
            } catch (InvokeContinuation e) {
                pending = e.continuation.segments;
                context.setDynamicEnv(e.continuation.dynEnv);
                value = e.value;
            }
        }
    }

    private Object call(Object procedure, Object[] arguments) {
        if (!(procedure instanceof SClosure closure)) {
            throw notAProcedure(procedure);
        }
        Object[] all = new Object[arguments.length + 1];
        all[0] = closure;
        System.arraycopy(arguments, 0, all, 1, arguments.length);
        return callNode.call(closure.callTarget, all);
    }

    /** {@code arguments} already includes the procedure at index 0. */
    private Object callWithProcedure(Object procedure, Object[] arguments) {
        if (!(procedure instanceof SClosure closure)) {
            throw notAProcedure(procedure);
        }
        return callNode.call(closure.callTarget, arguments);
    }

    @TruffleBoundary
    private static SchemeError notAProcedure(Object value) {
        return new SchemeError("not a procedure: " + Printer.print(value, Printer.Mode.WRITE));
    }

    /**
     * A copy of a suspended frame (design.md "Copying a frame"): the arguments and every slot,
     * operand stack included. The original is never resumed, so every resume is independent.
     */
    static MaterializedFrame copy(MaterializedFrame frame) {
        FrameDescriptor descriptor = frame.getFrameDescriptor();
        MaterializedFrame copy = Truffle.getRuntime().createMaterializedFrame(frame.getArguments().clone(), descriptor);
        frame.copyTo(0, copy, 0, descriptor.getNumberOfSlots());
        return copy;
    }

    /** Resumes a segment through a DirectCallNode cache keyed on its ContinuationRootNode (M0-02, M0-03). */
    @GenerateInline(false)
    abstract static class ResumeNode extends Node {
        abstract Object execute(ContinuationRootNode root, MaterializedFrame frame, Object value);

        @Specialization(guards = "root == cachedRoot", limit = "8")
        static Object doDirect(@SuppressWarnings("unused") ContinuationRootNode root, MaterializedFrame frame, Object value,
                        @Cached("root") ContinuationRootNode cachedRoot,
                        @Cached("create(cachedRoot.getCallTarget())") DirectCallNode callNode) {
            return callNode.call(frame, value);
        }

        @Specialization(replaces = "doDirect")
        static Object doIndirect(ContinuationRootNode root, MaterializedFrame frame, Object value,
                        @Cached IndirectCallNode callNode) {
            return callNode.call(root.getCallTarget(), frame, value);
        }
    }

    @Override
    public String getName() {
        return "<prompt>";
    }

    @Override
    public String toString() {
        return "<prompt>";
    }
}
