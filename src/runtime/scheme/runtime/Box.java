package scheme.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/**
 * The storage of a variable that is the target of {@code set!}, or a {@code letrec*} variable a
 * closure captures (design.md D2). Frames and closures hold the box, never the value, so frame
 * copies on continuation resume share it.
 *
 * <p>EXPLORATION: never a Scheme value, but the debugger reads frame slots, so a boxed local reaches
 * it as a Box: it shows as its contents. A custom scope (GenerateBytecode's tagTreeNodeLibrary)
 * would unbox instead (docs/interop-and-tools-exploration.md).
 */
@ExportLibrary(InteropLibrary.class)
public final class Box implements TruffleObject {

    public Object value;

    public Box(Object value) {
        this.value = value;
    }

    @ExportMessage
    boolean hasLanguageId() {
        return true;
    }

    @ExportMessage
    String getLanguageId() {
        return SchemeLanguage.ID;
    }

    @ExportMessage
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return value == null ? "#<unassigned>" : InteropValues.display(value);
    }
}
