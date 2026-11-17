package scheme.runtime.value;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import scheme.runtime.InteropValues;
import scheme.runtime.SchemeLanguage;

/**
 * A pair. Literal pairs are immutable (R7RS §3.4, design.md D1): {@code set-car!}, {@code set-cdr!}
 * and {@code list-set!} refuse them. Pairs made at run time are mutable. On HotSpot the flag costs
 * no space: a pair is a 12-byte header and two compressed references, 20 bytes padded to 24, and it
 * fits in the padding. In a native image (8-byte header) it takes a pair from 16 bytes to 24.
 */
@ExportLibrary(InteropLibrary.class)
public final class SPair implements TruffleObject {

    public Object car;
    public Object cdr;
    /** A literal's pair (quote, a self-evaluating vector's elements, quasiquote's constant parts). */
    public final boolean immutable;

    public SPair(Object car, Object cdr) {
        this.car = car;
        this.cdr = cdr;
        this.immutable = false;
    }

    public SPair(Object car, Object cdr, boolean immutable) {
        this.car = car;
        this.cdr = cdr;
        this.immutable = immutable;
    }

    // A list to interop (design.md D10): iterable, its elements up to the first non-pair (so an
    // improper list's last cdr is left out: its pairs' cdr members, below, show it). The iterator
    // walks the list lazily, never copying it.

    @ExportMessage
    boolean hasIterator() {
        return true;
    }

    @ExportMessage
    Object getIterator() {
        return new SListIterator(this);
    }

    // A pair that doesn't start a proper list is also an object to interop (design.md D10), whose
    // members are car and cdr, so that Java sees what the iterator leaves out: an improper list's
    // last cdr. A proper list has no members, so a host that asks for an Object gets an Iterable
    // of its elements, not a Map of car and cdr. A circular list isn't proper: it has members (and
    // iterates as before). The walk is made each time interop asks, and nothing is cached: a pair
    // stays two fields, and set-cdr! can change the answer. Both members are writable, as set-car!
    // and set-cdr! write them: pairs carry no literal flag (design.md D1).

    /** The names of a pair's members, in order. */
    private static final SVector MEMBERS = new SVector(new Object[]{SString.literal("car"), SString.literal("cdr")}, true);

    /** Whether the cdr chain from this pair ends in {@code '()}: false for a circular one. */
    @TruffleBoundary
    private boolean startsProperList() {
        // Floyd's cycle detection: fast moves two pairs per step, slow one.
        Object slow = this;
        Object fast = this;
        while (true) {
            if (!(fast instanceof SPair f1)) {
                return fast == SConstant.EMPTY_LIST;
            }
            if (!(f1.cdr instanceof SPair f2)) {
                return f1.cdr == SConstant.EMPTY_LIST;
            }
            fast = f2.cdr;
            slow = ((SPair) slow).cdr;
            if (fast == slow) {
                return false;
            }
        }
    }

    @ExportMessage
    boolean hasMembers() {
        return !startsProperList();
    }

    @ExportMessage
    Object getMembers(@SuppressWarnings("unused") boolean includeInternal) throws UnsupportedMessageException {
        if (startsProperList()) {
            throw UnsupportedMessageException.create();
        }
        return MEMBERS;
    }

    @ExportMessage(name = "isMemberReadable")
    @ExportMessage(name = "isMemberModifiable")
    boolean isMember(String member) {
        return ("car".equals(member) || "cdr".equals(member)) && !startsProperList();
    }

    @ExportMessage
    boolean isMemberInsertable(@SuppressWarnings("unused") String member) {
        return false;
    }

    @ExportMessage
    Object readMember(String member) throws UnsupportedMessageException, UnknownIdentifierException {
        if (startsProperList()) {
            throw UnsupportedMessageException.create();
        }
        if ("car".equals(member)) {
            return car;
        } else if ("cdr".equals(member)) {
            return cdr;
        }
        throw UnknownIdentifierException.create(member);
    }

    @ExportMessage
    void writeMember(String member, Object value) throws UnsupportedMessageException, UnknownIdentifierException, UnsupportedTypeException {
        if (startsProperList()) {
            throw UnsupportedMessageException.create();
        }
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
