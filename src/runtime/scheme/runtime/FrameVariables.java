package scheme.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.bytecode.BytecodeLocation;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.TagTreeNode;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.nodes.Node;

import scheme.runtime.value.SClosure;

/**
 * The variables that Scheme code at one bytecode index of a procedure can refer to, as tools show
 * them (plan.md M6-05): the root's named locals live there (parameters, and the variables of the
 * lets and letrec*s around it), the running closure's captured variables, and the fixed letrec*
 * variable the running closure is bound to (design.md D2). A {@link Box} stands for its contents.
 * Compiler temporaries and the variables of a macro's template are unnamed, so they don't show. An
 * inner binding of a name hides an outer one: a local hides a captured variable, and a later
 * local (of an inner let) an earlier one.
 *
 * <p>Only tools make these: the debugger's scopes ({@link FrameScope}) and expressions evaluated in
 * a frame ({@link FrameEvalNode}). Nothing on the execution path does.
 */
final class FrameVariables {

    /** {@link #slots}: the running closure itself. */
    private static final int SELF = -1;

    final BytecodeNode bytecode;
    final int bci;
    /** The visible names, in the order tools list them: locals, then captured variables. */
    final String[] names;
    /** Where each one is: a local offset ({@code >= 0}), {@link #SELF}, or {@code -2 - slot} for {@code captured[slot]}. */
    private final int[] slots;

    private FrameVariables(BytecodeNode bytecode, int bci, String[] names, int[] slots) {
        this.bytecode = bytecode;
        this.bci = bci;
        this.names = names;
        this.slots = slots;
    }

    /**
     * The variables at a node of a Scheme procedure: a tag tree node's, where it begins (or returns,
     * with {@code enter} false); any other node's, at the bytecode index the frame holds (design.md
     * D13). Null if the node isn't in Scheme code. With a frame, a local that hasn't been set yet (a
     * let's, while its init runs) is left out.
     */
    @TruffleBoundary
    static FrameVariables at(Node location, MaterializedFrame frame, boolean enter) {
        BytecodeNode bytecode = BytecodeNode.get(location);
        if (bytecode == null || !(bytecode.getBytecodeRootNode() instanceof SchemeRootNode root)) {
            return null;
        }
        MaterializedFrame own = resolve(bytecode, frame);
        int bci;
        if (location instanceof TagTreeNode tag) {
            bci = enter ? tag.getEnterBytecodeIndex() : tag.getReturnBytecodeIndex();
        } else {
            BytecodeLocation where = own == null ? null : bytecode.getBytecodeLocation(own, location);
            if (where == null) {
                return null;
            }
            bci = where.getBytecodeIndex();
        }
        Map<String, Integer> found = new LinkedHashMap<>();
        Object[] localNames = bytecode.getLocalNames(bci);
        for (int i = 0; i < localNames.length; i++) {
            if (localNames[i] instanceof String name && (own == null || bytecode.getLocalValue(bci, own, i) != null)) {
                found.put(name, i);
            }
        }
        if (root.selfName != null) {
            found.putIfAbsent(root.selfName, SELF);
        }
        if (root.capturedNames != null) {
            for (int i = 0; i < root.capturedNames.length; i++) {
                if (root.capturedNames[i] != null) {
                    found.putIfAbsent(root.capturedNames[i], -2 - i);
                }
            }
        }
        String[] names = new String[found.size()];
        int[] slots = new int[found.size()];
        int k = 0;
        for (Map.Entry<String, Integer> e : found.entrySet()) {
            names[k] = e.getKey();
            slots[k] = e.getValue();
            k++;
        }
        return new FrameVariables(bytecode, bci, names, slots);
    }

    /**
     * The frame the procedure's locals are in: the frame itself, or for a procedure resumed from a
     * continuation segment, the frame its continuation root runs it in. Null if neither.
     */
    static MaterializedFrame resolve(BytecodeNode bytecode, MaterializedFrame frame) {
        if (frame == null) {
            return null;
        }
        if (frame.getFrameDescriptor() == bytecode.getRootNode().getFrameDescriptor()) {
            return frame;
        }
        Object[] arguments = frame.getArguments();
        if (arguments.length > 0 && arguments[0] instanceof MaterializedFrame own && own.getFrameDescriptor() == bytecode.getRootNode().getFrameDescriptor()) {
            return own;
        }
        return null;
    }

    int indexOf(String name) {
        for (int k = 0; k < names.length; k++) {
            if (names[k].equals(name)) {
                return k;
            }
        }
        return -1;
    }

    /** What the variable's storage holds in the procedure's own frame: its value or its Box; null if unavailable. */
    @TruffleBoundary
    Object raw(MaterializedFrame own, int k) {
        if (own == null) {
            return null;
        }
        int slot = slots[k];
        if (slot >= 0) {
            return bytecode.getLocalValue(bci, own, slot);
        }
        Object[] arguments = own.getArguments();
        if (arguments.length == 0 || !(arguments[0] instanceof SClosure closure)) {
            return null;
        }
        if (slot == SELF) {
            return closure;
        }
        int index = -2 - slot;
        return index < closure.captured.length ? closure.captured[index] : null;
    }

    /** The variable's value: a Box's contents, or what its storage holds. */
    static Object value(Object raw) {
        return raw instanceof Box box ? box.get() : raw;
    }
}
