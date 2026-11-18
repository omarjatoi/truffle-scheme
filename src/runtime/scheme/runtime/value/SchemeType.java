package scheme.runtime.value;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import scheme.runtime.InteropValues;
import scheme.runtime.SchemeLanguage;
import scheme.runtime.staticobject.SRecord;

/**
 * A Scheme type as an interop meta object ({@code getMetaObject}, design.md D10): R7RS §3.2's
 * disjoint types, named after their predicates without the {@code ?} ({@code pair},
 * {@code procedure}, ...), plus the types of the other values a program can see. A record's meta
 * object is its {@link SRecordType} instead. Not a Scheme value: only interop sees it.
 */
@ExportLibrary(InteropLibrary.class)
public final class SchemeType implements TruffleObject {

    public static final SchemeType BOOLEAN = new SchemeType("boolean");
    /** Every number: exact and inexact, integer, rational, real and complex (D1). */
    public static final SchemeType NUMBER = new SchemeType("number");
    public static final SchemeType CHAR = new SchemeType("char");
    public static final SchemeType STRING = new SchemeType("string");
    public static final SchemeType SYMBOL = new SchemeType("symbol");
    public static final SchemeType PAIR = new SchemeType("pair");
    /** The empty list. */
    public static final SchemeType NULL = new SchemeType("null");
    public static final SchemeType VECTOR = new SchemeType("vector");
    public static final SchemeType BYTEVECTOR = new SchemeType("bytevector");
    public static final SchemeType PROCEDURE = new SchemeType("procedure");
    public static final SchemeType PORT = new SchemeType("port");
    public static final SchemeType EOF_OBJECT = new SchemeType("eof-object");
    public static final SchemeType ERROR_OBJECT = new SchemeType("error-object");
    public static final SchemeType ENVIRONMENT = new SchemeType("environment");
    public static final SchemeType PROMISE = new SchemeType("promise");
    public static final SchemeType RECORD_TYPE = new SchemeType("record-type");
    public static final SchemeType UNSPECIFIED = new SchemeType("unspecified");
    public static final SchemeType DEFAULT_OBJECT = new SchemeType("default-object");
    /** Zero or several values (D1). */
    public static final SchemeType VALUES = new SchemeType("values");

    private final String name;

    private SchemeType(String name) {
        this.name = name;
    }

    /**
     * The meta object of a Scheme value: a SchemeType, or a record's record type. Null for a foreign
     * value, and for the runtime's own objects (continuations, dynamic environments).
     */
    public static Object of(Object value) {
        if (value instanceof Boolean) {
            return BOOLEAN;
        } else if (value instanceof Long || value instanceof Double || value instanceof SBigInteger || value instanceof SRational || value instanceof SComplex) {
            return NUMBER;
        } else if (value instanceof SChar) {
            return CHAR;
        } else if (value instanceof SString) {
            return STRING;
        } else if (value instanceof SSymbol) {
            return SYMBOL;
        } else if (value instanceof SPair) {
            return PAIR;
        } else if (value instanceof SConstant c) {
            return c.metaObject();
        } else if (value instanceof SVector) {
            return VECTOR;
        } else if (value instanceof SBytevector) {
            return BYTEVECTOR;
        } else if (value instanceof SClosure) {
            return PROCEDURE;
        } else if (value instanceof SRecord r) {
            return r.type;
        } else if (value instanceof SPort) {
            return PORT;
        } else if (value instanceof SErrorObject) {
            return ERROR_OBJECT;
        } else if (value instanceof SEnvironment) {
            return ENVIRONMENT;
        } else if (value instanceof SPromise) {
            return PROMISE;
        } else if (value instanceof SRecordType) {
            return RECORD_TYPE;
        } else if (value instanceof MultipleValues) {
            return VALUES;
        }
        return null;
    }

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
    @TruffleBoundary
    boolean isMetaInstance(Object instance) {
        return of(InteropValues.toSchemePrimitive(instance)) == this;
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
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
