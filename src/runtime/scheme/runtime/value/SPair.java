package scheme.runtime.value;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import scheme.runtime.InteropValues;
import scheme.runtime.SchemeLanguage;

/** A mutable pair. */
@ExportLibrary(InteropLibrary.class)
public final class SPair implements TruffleObject {

    public Object car;
    public Object cdr;

    public SPair(Object car, Object cdr) {
        this.car = car;
        this.cdr = cdr;
    }

    // A list to interop (design.md D10): iterable, its elements up to the first non-pair (so an
    // improper list's last cdr is left out: its cdr member, below, shows it). The iterator walks
    // the list lazily, never copying it.

    @ExportMessage
    boolean hasIterator() {
        return true;
    }

    @ExportMessage
    Object getIterator() {
        return new SListIterator(this);
    }

    // A pair to interop (design.md D10): also an object whose members are car and cdr, so that
    // Java sees what the iterator leaves out, an improper list's last cdr. Both are writable, as
    // set-car! and set-cdr! write them: pairs carry no literal flag (design.md D1).

    /** The names of a pair's members, in order. */
    private static final SVector MEMBERS = new SVector(new Object[]{SString.literal("car"), SString.literal("cdr")}, true);

    @ExportMessage
    boolean hasMembers() {
        return true;
    }

    @ExportMessage
    Object getMembers(@SuppressWarnings("unused") boolean includeInternal) {
        return MEMBERS;
    }

    @ExportMessage(name = "isMemberReadable")
    @ExportMessage(name = "isMemberModifiable")
    static boolean isMember(@SuppressWarnings("unused") SPair receiver, String member) {
        return "car".equals(member) || "cdr".equals(member);
    }

    @ExportMessage
    boolean isMemberInsertable(@SuppressWarnings("unused") String member) {
        return false;
    }

    @ExportMessage
    Object readMember(String member) throws UnknownIdentifierException {
        if ("car".equals(member)) {
            return car;
        } else if ("cdr".equals(member)) {
            return cdr;
        }
        throw UnknownIdentifierException.create(member);
    }

    @ExportMessage
    void writeMember(String member, Object value) throws UnknownIdentifierException, UnsupportedTypeException {
        if ("car".equals(member)) {
            car = InteropValues.toScheme(value);
        } else if ("cdr".equals(member)) {
            cdr = InteropValues.toScheme(value);
        } else {
            throw UnknownIdentifierException.create(member);
        }
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
        return SchemeType.PAIR;
    }

    @ExportMessage
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return InteropValues.display(this);
    }
}
