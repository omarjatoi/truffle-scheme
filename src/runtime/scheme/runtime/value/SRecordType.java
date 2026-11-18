package scheme.runtime.value;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.staticobject.StaticProperty;

import scheme.runtime.InteropValues;
import scheme.runtime.SchemeLanguage;
import scheme.runtime.staticobject.SRecord;

/**
 * A record type from {@code define-record-type}: each evaluation of a definition makes a new one.
 * Its records are static objects of the shape for its number of fields ({@link RecordShapes}).
 */
@ExportLibrary(InteropLibrary.class)
public final class SRecordType implements TruffleObject {

    public final String name;
    public final String[] fieldNames;
    /** Makes a record of this type, its fields unset (null): the constructor sets them all. */
    public final SRecord.Factory factory;
    /** The fields' properties, in {@link #fieldNames}' order. */
    @CompilationFinal(dimensions = 1) public final StaticProperty[] properties;

    public SRecordType(String name, String[] fieldNames, RecordShapes shapes) {
        this.name = name;
        this.fieldNames = fieldNames;
        RecordShapes.Layout layout = shapes.layout(fieldNames.length);
        this.factory = layout.shape().getFactory();
        this.properties = layout.properties();
    }

    /** The index of the field named {@code field}, or -1. */
    public int fieldIndex(String field) {
        for (int i = 0; i < fieldNames.length; i++) {
            if (fieldNames[i].equals(field)) {
                return i;
            }
        }
        return -1;
    }

    // The meta object of its records (design.md D10).

    @ExportMessage
    boolean isMetaObject() {
        return true;
    }

    @ExportMessage
    Object getMetaQualifiedName() {
        return name;
    }

    @ExportMessage
    Object getMetaSimpleName() {
        return name;
    }

    @ExportMessage
    boolean isMetaInstance(Object instance) {
        return instance instanceof SRecord r && r.type == this;
    }

    // Interop (design.md D10).

    @ExportMessage
    boolean hasLanguageId() {
        return true;
    }

    @ExportMessage
    String getLanguageId() {
        return SchemeLanguage.ID;
    }

    @ExportMessage
    boolean hasMetaObject() {
        return true;
    }

    @ExportMessage
    Object getMetaObject() {
        return SchemeType.RECORD_TYPE;
    }

    @ExportMessage
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return InteropValues.display(this);
    }
}
