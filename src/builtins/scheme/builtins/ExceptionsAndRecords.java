package scheme.builtins;

import java.util.List;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.runtime.PrimitiveNode;
import scheme.runtime.PrimitiveSpec;
import scheme.runtime.SchemeContext;
import scheme.runtime.SchemeError;
import scheme.runtime.printer.Printer;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SErrorObject;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SRecord;
import scheme.runtime.value.SRecordType;
import scheme.runtime.value.SSymbol;
import scheme.runtime.value.SVector;

/**
 * Leaf primitives under the prelude's exceptions (raise, error, guard: exceptions.scm, design.md D8)
 * and records (define-record-type: records.scm, D1). None calls Scheme (rule 1).
 */
final class ExceptionsAndRecords {

    private ExceptionsAndRecords() {
    }

    static List<PrimitiveSpec> primitives() {
        return List.of(
                        new PrimitiveSpec("%set-raise!", 1, 1, SetRaise::new),
                        new PrimitiveSpec("%raise-uncaught", 1, 1, RaiseUncaught::new),
                        new PrimitiveSpec("%make-error-object", 2, 2, MakeErrorObject::new),
                        new PrimitiveSpec("error-object?", 1, 1, () -> new ErrorObjectOp(ErrorObjectOp.TEST)),
                        new PrimitiveSpec("error-object-message", 1, 1, () -> new ErrorObjectOp(ErrorObjectOp.MESSAGE)),
                        new PrimitiveSpec("error-object-irritants", 1, 1, () -> new ErrorObjectOp(ErrorObjectOp.IRRITANTS)),
                        new PrimitiveSpec("read-error?", 1, 1, () -> new ErrorObjectOp(ErrorObjectOp.READ)),
                        new PrimitiveSpec("file-error?", 1, 1, () -> new ErrorObjectOp(ErrorObjectOp.FILE)),
                        new PrimitiveSpec("%make-record-type", 2, 2, MakeRecordType::new),
                        new PrimitiveSpec("%record-index", 2, 2, RecordIndex::new),
                        new PrimitiveSpec("%make-record", 2, -1, MakeRecord::new),
                        new PrimitiveSpec("%record?", 2, 2, RecordTest::new),
                        new PrimitiveSpec("%record-ref", 3, 3, () -> new RecordAccess(false)),
                        new PrimitiveSpec("%record-set!", 4, 4, () -> new RecordAccess(true)));
    }

    /** Installs the prelude's raise, which call sites call with primitive errors (D8). */
    static final class SetRaise extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            SchemeContext.get(this).raise = args[0];
            return SConstant.UNSPECIFIED;
        }
    }

    /** {@code (%raise-uncaught obj)}: raise found no handler, so the program ends (D8). */
    static final class RaiseUncaught extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            throw uncaught(args[0]);
        }

        @TruffleBoundary
        private static SchemeError uncaught(Object raised) {
            String message;
            if (raised instanceof SErrorObject e) {
                StringBuilder text = new StringBuilder(Printer.print(e.message, Printer.Mode.DISPLAY));
                for (Object l = e.irritants; l instanceof SPair p; l = p.cdr) {
                    text.append(' ').append(Printer.print(p.car, Printer.Mode.WRITE));
                }
                message = text.toString();
            } else {
                message = "uncaught exception: " + Printer.print(raised, Printer.Mode.WRITE);
            }
            return SchemeError.uncaught(message, raised);
        }
    }

    /** {@code (%make-error-object message irritants)}: what {@code error} raises. */
    static final class MakeErrorObject extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return new SErrorObject(args[0], args[1], SErrorObject.Kind.OTHER);
        }
    }

    static final class ErrorObjectOp extends PrimitiveNode {
        static final int TEST = 0;
        static final int MESSAGE = 1;
        static final int IRRITANTS = 2;
        static final int READ = 3;
        static final int FILE = 4;

        private final int op;

        ErrorObjectOp(int op) {
            this.op = op;
        }

        @Override
        public Object execute(Object[] args) {
            Object x = args[0];
            switch (op) {
                case TEST:
                    return x instanceof SErrorObject;
                case READ:
                    return x instanceof SErrorObject e && e.kind == SErrorObject.Kind.READ;
                case FILE:
                    return x instanceof SErrorObject e && e.kind == SErrorObject.Kind.FILE;
                default:
                    if (!(x instanceof SErrorObject e)) {
                        throw notA(op == MESSAGE ? "error-object-message" : "error-object-irritants", "error object", x);
                    }
                    return op == MESSAGE ? e.message : e.irritants;
            }
        }
    }

    /** {@code (%make-record-type 'name '(field ...))}. */
    static final class MakeRecordType extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return make(args[0], args[1]);
        }

        @TruffleBoundary
        private static SRecordType make(Object name, Object fields) {
            Object[] names = Lists.toArray("define-record-type", fields);
            String[] strings = new String[names.length];
            for (int i = 0; i < names.length; i++) {
                strings[i] = names[i].toString();
            }
            return new SRecordType(Printer.print(name, Printer.Mode.DISPLAY), strings);
        }
    }

    /** {@code (%record-index type 'field)}: the field's index, at definition time. */
    static final class RecordIndex extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return index((SRecordType) args[0], args[1]);
        }

        @TruffleBoundary
        private static long index(SRecordType type, Object field) {
            String name = field instanceof SSymbol ? field.toString() : "";
            for (int i = 0; i < type.fieldNames.length; i++) {
                if (type.fieldNames[i].equals(name)) {
                    return i;
                }
            }
            throw new SchemeError("define-record-type: " + Printer.print(field, Printer.Mode.WRITE) + " is not a field of " + type.name);
        }
    }

    /** {@code (%make-record type #(index ...) value ...)}: fields not given are unspecified. */
    static final class MakeRecord extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            SRecordType type = (SRecordType) args[0];
            Object[] indices = ((SVector) args[1]).elements;
            Object[] fields = new Object[type.fieldNames.length];
            java.util.Arrays.fill(fields, SConstant.UNSPECIFIED);
            for (int i = 0; i < indices.length; i++) {
                fields[(int) (long) (Long) indices[i]] = args[i + 2];
            }
            return new SRecord(type, fields);
        }
    }

    /** {@code (%record? type x)}. */
    static final class RecordTest extends PrimitiveNode {
        @Override
        public Object execute(Object[] args) {
            return args[1] instanceof SRecord r && r.type == args[0];
        }
    }

    /** {@code (%record-ref type index r)} and {@code (%record-set! type index r value)}. */
    static final class RecordAccess extends PrimitiveNode {
        private final boolean set;

        RecordAccess(boolean set) {
            this.set = set;
        }

        @Override
        public Object execute(Object[] args) {
            SRecordType type = (SRecordType) args[0];
            int index = (int) (long) (Long) args[1];
            if (!(args[2] instanceof SRecord r) || r.type != type) {
                throw notA(type.fieldNames[index], "record of type " + type.name, args[2]);
            }
            if (set) {
                r.fields[index] = args[3];
                return SConstant.UNSPECIFIED;
            }
            return r.fields[index];
        }
    }

    @TruffleBoundary
    static SchemeError notA(String who, String what, Object value) {
        return new SchemeError(who + ": not a " + what + ": " + Printer.print(value, Printer.Mode.WRITE));
    }
}
