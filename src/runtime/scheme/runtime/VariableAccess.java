package scheme.runtime;

import java.util.ArrayList;
import java.util.List;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.bytecode.TagTreeNode;
import com.oracle.truffle.api.instrumentation.StandardTags.ReadVariableTag;
import com.oracle.truffle.api.instrumentation.StandardTags.WriteVariableTag;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import scheme.runtime.value.SString;
import scheme.runtime.value.SVector;

/**
 * The node object of a read or a write of a variable, as tools see it (design.md D13, "Variables'
 * reads and writes"): its one member, {@code readVariableName} or {@code writeVariableName}, is the
 * variable's name, as Truffle's {@code ReadVariableTag} and {@code WriteVariableTag} ask. The node's
 * source section is the variable's: the identifier for a read, the {@code set!} or
 * {@code define} form for a write.
 *
 * <p>The name isn't stored anywhere for it: it is found when a tool asks
 * ({@link SchemeRootNode#getInstrumentationNodeObject}), in the instructions the tag encloses
 * (which other tags they have depends on what tools asked for). A read encloses only the load of
 * the variable: a local, a captured variable (the running closure, then {@code LoadCaptured}), the
 * running closure itself (a fixed letrec* variable in its own lambda, design.md D2), or a global,
 * and a box's {@code BoxRef} if it is assigned. A write's own operation is its last
 * {@code GlobalSet}, {@code GlobalDefine} or {@code BoxSet}, after the value's instructions (which
 * may assign other variables): a global's names its cell; a box's takes the box as its first
 * operand, the load the tag begins with. A local's name is the one its scope shows
 * ({@link FrameVariables}), a captured variable's its root's {@code capturedNames}, the running
 * closure's its root's {@code selfName}, and a global's its cell's. Nothing on the execution path
 * makes these.
 */
@ExportLibrary(InteropLibrary.class)
final class VariableAccess implements TruffleObject {

    private final String member;
    private final String name;

    private VariableAccess(String member, String name) {
        this.member = member;
        this.name = name;
    }

    /** The node object of a tagged read or write in {@code root}; null for any other tagged node. */
    @TruffleBoundary
    static Object of(SchemeRootNode root, TagTreeNode node) {
        boolean read = node.hasTag(ReadVariableTag.class);
        if (!read && !node.hasTag(WriteVariableTag.class)) {
            return null;
        }
        String name = name(root, node, read);
        return name == null ? null : new VariableAccess(read ? ReadVariableTag.NAME : WriteVariableTag.NAME, name);
    }

    /** The name of the variable the tagged read or write accesses (see above), or null. */
    private static String name(SchemeRootNode root, TagTreeNode node, boolean read) {
        BytecodeNode bytecode = node.getBytecodeNode();
        List<Instruction> instructions = new ArrayList<>();
        int end = node.getReturnBytecodeIndex();
        for (int bci = node.getEnterBytecodeIndex(); bci <= end;) {
            Instruction instruction = bytecode.getInstruction(bci);
            if (!instruction.isInstrumentation()) {
                instructions.add(instruction);
            }
            bci = instruction.getNextBytecodeIndex();
        }
        if (!read) {
            for (int k = instructions.size() - 1; k >= 0; k--) {
                String operation = instructions.get(k).getName();
                if (operation.startsWith(GLOBAL_SET) || operation.startsWith(GLOBAL_DEFINE)) {
                    return variable(root, bytecode, instructions.get(k));
                }
                if (operation.startsWith(BOX_SET)) {
                    break;
                }
            }
        }
        if (instructions.isEmpty()) {
            return null;
        }
        Instruction first = instructions.get(0);
        if (isClosure(first)) {
            boolean captured = instructions.size() > 1 && instructions.get(1).getName().startsWith(LOAD_CAPTURED);
            return captured ? variable(root, bytecode, instructions.get(1)) : root.selfName;
        }
        return variable(root, bytecode, first);
    }

    /** Whether {@code instruction} loads the running closure, frame argument 0. */
    private static boolean isClosure(Instruction instruction) {
        if (!instruction.getName().startsWith(LOAD_ARGUMENT)) {
            return false;
        }
        for (Instruction.Argument argument : instruction.getArguments()) {
            if (argument.getKind() == Instruction.Argument.Kind.INTEGER && argument.asInteger() == 0) {
                return true;
            }
        }
        return false;
    }

    private static final String LOAD_ARGUMENT = "load.argument";
    private static final String LOAD_CAPTURED = "c.LoadCaptured";
    private static final String BOX_SET = "c.BoxSet";
    private static final String GLOBAL_SET = "c.GlobalSet";
    private static final String GLOBAL_DEFINE = "c.GlobalDefine";

    /** The name of the local, captured variable or global whose storage {@code instruction} accesses, or null. */
    private static String variable(SchemeRootNode root, BytecodeNode bytecode, Instruction instruction) {
        for (Instruction.Argument argument : instruction.getArguments()) {
            if (argument.getKind() == Instruction.Argument.Kind.LOCAL_OFFSET) {
                if (bytecode.getLocalName(instruction.getBytecodeIndex(), argument.asLocalOffset()) instanceof String local) {
                    return local;
                }
            } else if (argument.getKind() == Instruction.Argument.Kind.CONSTANT) {
                Object constant = argument.asConstant();
                if (constant instanceof GlobalCell cell) {
                    return cell.name.toString();
                }
                if (constant instanceof Integer slot && instruction.getName().startsWith(LOAD_CAPTURED) && root.capturedNames != null && slot < root.capturedNames.length) {
                    return root.capturedNames[slot];
                }
            }
        }
        return null;
    }

    @ExportMessage
    boolean hasMembers() {
        return true;
    }

    @ExportMessage
    @TruffleBoundary
    Object getMembers(@SuppressWarnings("unused") boolean includeInternal) {
        return new SVector(new Object[]{SString.of(member)}, true);
    }

    @ExportMessage
    @TruffleBoundary
    boolean isMemberReadable(String m) {
        return member.equals(m);
    }

    @ExportMessage
    @TruffleBoundary
    Object readMember(String m) throws UnknownIdentifierException {
        if (!member.equals(m)) {
            throw UnknownIdentifierException.create(m);
        }
        return name;
    }
}
