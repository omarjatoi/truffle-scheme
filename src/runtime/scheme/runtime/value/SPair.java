package scheme.runtime.value;

import com.oracle.truffle.api.CompilerDirectives;
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
 * A pair made at run time, which is mutable. A literal's pairs are {@link LiteralPair}s, which
 * {@code set-car!}, {@code set-cdr!} and {@code list-set!} refuse (R7RS §3.4, design.md D1). The
 * subclass is the mark, not a field, so a pair stays a header and two references (16 bytes in a
 * native image, where a flag would take it to 24), and the mutators' fast path is one exact-class
 * compare ({@link #isMutable}), which is also their type check.
 */
@ExportLibrary(InteropLibrary.class)
public class SPair implements TruffleObject {

    public Object car;
    public Object cdr;

    public SPair(Object car, Object cdr) {
        this.car = car;
        this.cdr = cdr;
    }

    /**
     * Whether {@code x} is a pair made at run time: exactly an {@code SPair}, not a
     * {@link LiteralPair}. Compiled, one compare of {@code x}'s class, after which
     * {@code CompilerDirectives.castExact(x, SPair.class)} costs nothing and the compiler knows the
     * exact class, so a later check of the same value folds away.
     */
    public static boolean isMutable(Object x) {
        return CompilerDirectives.isExact(x, SPair.class);
    }

    /**
     * {@code x} if it is a pair, either class, else null: for loops down a list. Two compares of
     * {@code x}'s class, each of which proves its cast, where {@code instanceof SPair}, now that
     * {@code SPair} has a subclass, would also load a supertype of {@code x}'s class.
     */
    public static SPair asPair(Object x) {
        if (x != null) {
            if (x.getClass() == SPair.class) {
                return (SPair) x;
            }
            if (x.getClass() == LiteralPair.class) {
                return (LiteralPair) x;
            }
        }
        return null;
    }

    /** {@code x}, which {@link #isMutable} accepted. */
    public static SPair asMutable(Object x) {
        return CompilerDirectives.castExact(x, SPair.class);
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
    // and set-cdr! write them, except a literal's, which are constants (design.md D1).

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
    boolean isMember(String member) {
        return ("car".equals(member) || "cdr".equals(member)) && !startsProperList();
    }

    @ExportMessage
    boolean isMemberModifiable(String member) {
        return !(this instanceof LiteralPair) && isMember(member);
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
        if (this instanceof LiteralPair || startsProperList()) {
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
