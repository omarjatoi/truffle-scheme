package scheme.builtins;

import java.util.ArrayList;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.runtime.SchemeError;
import scheme.runtime.printer.Printer;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SErrorObject;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SString;

/** Helpers for primitives that take lists. */
final class Lists {

    private Lists() {
    }

    /**
     * The elements of a proper list, or a SchemeError naming the primitive ({@link #notAProperList}).
     * Circular lists are rejected (design.md D9): a slow pointer follows at half speed (Floyd's
     * tortoise and hare) and meets the walk only on a cycle.
     */
    @TruffleBoundary
    static Object[] toArray(String who, Object list) {
        ArrayList<Object> items = new ArrayList<>();
        Object slow = list;
        Object rest = list;
        while (rest instanceof SPair p) {
            items.add(p.car);
            rest = p.cdr;
            if ((items.size() & 1) == 0) {
                slow = ((SPair) slow).cdr;
                if (slow == rest) {
                    throw notAProperList(who, list);
                }
            }
        }
        if (rest != SConstant.EMPTY_LIST) {
            throw notAProperList(who, list);
        }
        return items.toArray();
    }

    /**
     * {@code who} needs a proper list, and {@code list} is improper or circular (R7RS §6.4: "it is
     * an error"; we raise, design.md D9). The list is the irritant, so printing the error terminates
     * on a cycle (write's datum labels).
     */
    @TruffleBoundary
    static SchemeError notAProperList(String who, Object list) {
        return new SchemeError(new SErrorObject(SString.of(who + ": not a proper list"), new SPair(list, SConstant.EMPTY_LIST), SErrorObject.Kind.OTHER));
    }

    @TruffleBoundary
    static SchemeError notAPair(String who, Object value) {
        return new SchemeError(who + ": not a pair: " + Printer.print(value, Printer.Mode.WRITE));
    }
}
