package scheme.builtins;

import java.util.ArrayList;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.runtime.SchemeError;
import scheme.runtime.printer.Printer;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SPair;

/** Helpers for primitives that take lists. */
final class Lists {

    private Lists() {
    }

    /** The elements of a proper list, or a SchemeError naming the primitive. Circular lists are rejected. */
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
                if (slow == rest && rest instanceof SPair) {
                    throw new SchemeError(who + ": circular list");
                }
            }
        }
        if (rest != SConstant.EMPTY_LIST) {
            throw new SchemeError(who + ": not a proper list: " + Printer.print(list, Printer.Mode.WRITE));
        }
        return items.toArray();
    }

    @TruffleBoundary
    static SchemeError notAPair(String who, Object value) {
        return new SchemeError(who + ": not a pair: " + Printer.print(value, Printer.Mode.WRITE));
    }
}
