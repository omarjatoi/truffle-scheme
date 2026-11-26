package scheme.builtins;

import java.util.ArrayList;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.runtime.SchemeError;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SPair;

/** Helpers for primitives that take lists. */
final class Lists {

    private Lists() {
    }

    /**
     * The elements of a proper list, {@code who}'s argument number {@code position}, or a SchemeError naming both ({@link #notAProperList}).
     * Circular lists are rejected (design.md D9): a slow pointer follows at half speed (Floyd's
     * tortoise and hare) and meets the walk only on a cycle.
     */
    @TruffleBoundary
    static Object[] toArray(String who, int position, Object list) {
        ArrayList<Object> items = new ArrayList<>();
        Object slow = list;
        Object rest = list;
        for (SPair p; (p = SPair.asPair(rest)) != null;) {
            items.add(p.car);
            rest = p.cdr;
            if ((items.size() & 1) == 0) {
                slow = ((SPair) slow).cdr;
                if (slow == rest) {
                    throw notAProperList(who, position, list);
                }
            }
        }
        if (rest != SConstant.EMPTY_LIST) {
            throw notAProperList(who, position, list);
        }
        return items.toArray();
    }

    /**
     * {@code who} needs a proper list, and {@code list} is improper or circular (R7RS §6.4: "it is
     * an error"; we raise, design.md D9). The list is the irritant, so printing the error terminates
     * on a cycle (write's datum labels).
     */
    @TruffleBoundary
    static SchemeError notAProperList(String who, int position, Object list) {
        return SchemeError.argument(who, position, "proper list", list);
    }

    @TruffleBoundary
    static SchemeError notAPair(String who, int position, Object value) {
        return SchemeError.argument(who, position, "pair", value);
    }
}
