package scheme.compiler;

import java.util.ArrayList;
import java.util.List;

import scheme.runtime.value.SBigInteger;
import scheme.runtime.value.SComplex;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SRational;

/**
 * A call of {@code memv} or {@code memq} with a quoted list (plan.md M8-05), which is what
 * {@code case} expands to for each clause (prelude syntax.scm). It compiles to a comparison of the
 * key with each datum by {@code eq?}'s operation, and its value is the primitive's: the tail of the
 * list whose car matched, or #f. A call of the primitive ran a loop behind a boundary, with
 * {@code eqv?}'s generic comparison, for every clause.
 *
 * <p>Only a call of the primitives' own cells ({@link scheme.runtime.GlobalCell#isFixed()}, as for
 * {@link Intrinsic}), with a proper list of at most {@link #LIMIT} datums. For {@code memv}, every
 * datum must be one that {@code eq?} compares as {@code eqv?} does (design.md D1): anything but an
 * exact number that isn't a fixnum.
 */
final class ConstantMember {

    /** The most datums a list may have: past it, the primitive's loop is called. */
    static final int LIMIT = 16;

    private ConstantMember() {
    }

    /** The list's pairs, if {@code call} is one (above); else null. */
    static List<SPair> pairs(Ast.Call call) {
        if (!(call.procedure instanceof Ast.GlobalRef g && g.cell.isFixed() && call.arguments.size() == 2 &&
                        call.arguments.get(1) instanceof Ast.Const list)) {
            return null;
        }
        String name = g.cell.name.toString();
        boolean eqv = name.equals("memv");
        if (!eqv && !name.equals("memq")) {
            return null;
        }
        List<SPair> pairs = new ArrayList<>();
        Object rest = list.value;
        while (rest instanceof SPair p) {
            if (pairs.size() == LIMIT || eqv && !eqIsEqv(p.car)) {
                return null;
            }
            pairs.add(p);
            rest = p.cdr;
        }
        // Not a proper list: the primitive's error. (A circular one ran into the limit.)
        return rest == SConstant.EMPTY_LIST ? pairs : null;
    }

    static boolean isConstantMember(Ast.Call call) {
        return pairs(call) != null;
    }

    /** Whether eq? compares {@code datum} with any object as eqv? does. */
    private static boolean eqIsEqv(Object datum) {
        return !(datum instanceof SBigInteger || datum instanceof SRational || datum instanceof SComplex);
    }
}
