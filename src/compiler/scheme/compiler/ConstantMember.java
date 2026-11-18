package scheme.compiler;

import java.util.ArrayList;
import java.util.List;

import scheme.runtime.value.SBigInteger;
import scheme.runtime.value.SChar;
import scheme.runtime.value.SComplex;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SRational;

/**
 * A call of {@code memv} or {@code memq} with a quoted list (plan.md M8-05), which is what
 * {@code case} expands to for each clause (prelude syntax.scm), or of {@code assv} or {@code assq}
 * with a quoted alist (M9-05). It compiles to a comparison of the key with each datum (each entry's
 * car, for an alist) by {@code eq?}, and its value is the primitive's: the tail of the list whose car
 * matched (the entry whose car matched), or #f. A call of the primitive ran a loop behind a boundary,
 * with {@code eqv?}'s generic comparison. {@code memv} and {@code memq} compile to a chain of
 * {@code Eq} operations; {@code assv} and {@code assq} to one operation, {@code Assoc}, whose loop
 * the interpreter runs in Java (a chain took longer than the call in the interpreter, M9-05).
 *
 * <p>Only a call of the primitives' own cells ({@link scheme.runtime.GlobalCell#isFixed()}, as for
 * {@link Intrinsic}), with a proper list of at most {@link #LIMIT} datums, and for an alist, every
 * one a pair (else the primitive's error, when it gets that far). For {@code memv} and
 * {@code assv}, every datum (every key) must be one that {@code eq?} compares as {@code eqv?} does
 * (design.md D1): anything but an exact number that isn't a fixnum.
 *
 * <p>A call of {@code memv} or {@code memq} whose quoted list is longer, and of characters only
 * ({@link #charList}, M12-04), compiles to one {@code MemberChar} operation instead, which finds the
 * key by a binary search of their code points.
 */
final class ConstantMember {

    /** The most datums a list may have: past it, the primitive's loop is called. */
    static final int LIMIT = 16;

    /** One comparison: when the key is {@code eq?} to {@code datum}, the call's value is {@code value}. */
    record Match(Object datum, Object value) {
    }

    private ConstantMember() {
    }

    /** The comparisons, in order, if {@code call} is one (above); else null. */
    static List<Match> matches(Ast.Call call) {
        if (!(call.procedure instanceof Ast.GlobalRef g && g.cell.isFixed() && call.arguments.size() == 2 &&
                        call.arguments.get(1) instanceof Ast.Const list)) {
            return null;
        }
        String name = g.cell.name.toString();
        boolean eqv = name.equals("memv") || name.equals("assv");
        boolean alist = name.equals("assq") || name.equals("assv");
        if (!eqv && !alist && !name.equals("memq")) {
            return null;
        }
        List<Match> matches = new ArrayList<>();
        Object rest = list.value;
        while (rest instanceof SPair p) {
            if (matches.size() == LIMIT) {
                return null;
            }
            Match m;
            if (!alist) {
                m = new Match(p.car, p);
            } else if (p.car instanceof SPair entry) {
                m = new Match(entry.car, entry);
            } else {
                return null;
            }
            if (eqv && !eqIsEqv(m.datum())) {
                return null;
            }
            matches.add(m);
            rest = p.cdr;
        }
        // Not a proper list: the primitive's error. (A circular one ran into the limit.)
        return rest == SConstant.EMPTY_LIST ? matches : null;
    }

    /** Whether {@code call}, one of these, is of assq or assv. */
    static boolean isAlist(Ast.Call call) {
        String name = ((Ast.GlobalRef) call.procedure).cell.name.toString();
        return name.equals("assq") || name.equals("assv");
    }

    static boolean isConstantMember(Ast.Call call) {
        return matches(call) != null || charList(call) != null;
    }

    /** The most datums {@link #charList} takes: a longer list (or a circular one) calls the primitive. */
    static final int CHAR_LIMIT = 4096;

    /**
     * The quoted list of a call of {@code memv} or {@code memq} with more than {@link #LIMIT} datums,
     * every one a character, which compiles to one {@code MemberChar} operation, a binary search of
     * their code points (design.md D17, "The native launcher's steady state"); else null.
     */
    static Object charList(Ast.Call call) {
        if (!(call.procedure instanceof Ast.GlobalRef g && g.cell.isFixed() && call.arguments.size() == 2 &&
                        call.arguments.get(1) instanceof Ast.Const list)) {
            return null;
        }
        String name = g.cell.name.toString();
        if (!name.equals("memv") && !name.equals("memq")) {
            return null;
        }
        int n = 0;
        Object rest = list.value;
        while (rest instanceof SPair p) {
            if (!(p.car instanceof SChar) || ++n > CHAR_LIMIT) {
                return null;
            }
            rest = p.cdr;
        }
        return rest == SConstant.EMPTY_LIST && n > LIMIT ? list.value : null;
    }

    /** Whether eq? compares {@code datum} with any object as eqv? does. */
    private static boolean eqIsEqv(Object datum) {
        return !(datum instanceof SBigInteger || datum instanceof SRational || datum instanceof SComplex);
    }
}
