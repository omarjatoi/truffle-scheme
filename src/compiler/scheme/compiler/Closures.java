package scheme.compiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import scheme.compiler.Ast.Expr;
import scheme.compiler.Ast.Lambda;
import scheme.compiler.Ast.Var;
import scheme.runtime.GlobalCell;

/**
 * Closure conversion facts for a unit (design.md D2), computed after expansion (M5-05): which root
 * holds each variable, each lambda's free variables, which variables closures capture, and which
 * {@code letrec*} variables are fixed.
 *
 * <p>The expander records captures as it resolves references, but some lambdas it saw are no
 * longer closures: a {@code let}'s lambda became an {@link Ast.Let}, whose variables are locals of
 * the root that runs it. So the facts are computed again here, over the final AST.
 *
 * <p><b>Fixing Letrec</b> (Waddell, Sarkar and Dybvig 2005). A {@code letrec*} variable that a
 * closure captures is boxed (D2), because the closure is made before the variable is initialized.
 * A variable is <em>fixed</em>, and not boxed, when it is never assigned, its init is a lambda, and
 * no init before the last non-lambda init that precedes its own mentions it. Then the only closures
 * that can capture it before it is initialized are the lambdas of the run of lambda inits it ends,
 * and no Scheme code runs between their making and its initialization. The code generator patches
 * its value into those closures right after it makes its own; a lambda's own variable needs no
 * slot at all, since in its root it is the running closure (frame argument 0). A frame copy on
 * continuation resume (D2) only sees fixed variables after they are initialized, and they are never
 * assigned, so their locals can be copied.
 *
 * <p><b>Contification</b> (plan.md M7-02). A named {@code let} or a {@code do} in tail position is a
 * loop of the root that enters it, when nothing else calls it ({@link #contify}).
 *
 * <p>No Java recursion proportional to nesting depth (AGENTS.md rule 4): explicit stacks.
 */
final class Closures {

    private Closures() {
    }

    /**
     * {@code callers}: the primitives that call a procedure they are given at the call site that
     * calls them ({@code apply}, {@code call-with-values}): see {@link #contify}.
     */
    static void analyze(Lambda unit, Set<GlobalCell> callers) {
        fix(unit);
        contify(unit, callers);
        resolve(unit);
    }

    // ------------------------------------------------------------------------------------------
    // Fixing Letrec

    /** Marks the fixed variables of every letrec* in the unit. */
    private static void fix(Lambda unit) {
        Deque<Expr> stack = new ArrayDeque<>();
        stack.push(unit.body);
        while (!stack.isEmpty()) {
            Expr e = stack.pop();
            if (e instanceof Ast.Letrec l) {
                fix(l);
            }
            pushChildren(e, stack);
        }
    }

    private static void fix(Ast.Letrec l) {
        Set<Var> group = identitySet();
        group.addAll(l.vars);
        // Mentioned by an init before the last non-lambda init so far; mentioned by a lambda init since.
        Set<Var> blocked = identitySet();
        Set<Var> pending = identitySet();
        for (int j = 0; j < l.vars.size(); j++) {
            Var v = l.vars.get(j);
            if (l.inits.get(j) instanceof Lambda fn) {
                for (Var w : fn.free.keySet()) {
                    if (group.contains(w)) {
                        pending.add(w);
                    }
                }
                if (!v.assigned && !blocked.contains(v)) {
                    v.fixed = true;
                    v.self = fn;
                    fn.self = v;
                }
            } else {
                blocked.addAll(pending);
                pending.clear();
                mentions(l.inits.get(j), group, blocked);
            }
        }
    }

    /**
     * Adds to {@code out} the variables of {@code group} that {@code e} refers to, in it or in a
     * lambda in it. The expander's free variables of a lambda include every variable referred to
     * in it from outside: at least the ones still free after {@link #resolve}.
     */
    private static void mentions(Expr e, Set<Var> group, Set<Var> out) {
        Deque<Expr> stack = new ArrayDeque<>();
        stack.push(e);
        while (!stack.isEmpty()) {
            Expr x = stack.pop();
            if (x instanceof Lambda fn) {
                for (Var w : fn.free.keySet()) {
                    if (group.contains(w)) {
                        out.add(w);
                    }
                }
                continue;
            }
            if (x instanceof Ast.LocalRef r && group.contains(r.var)) {
                out.add(r.var);
            } else if (x instanceof Ast.LocalSet s && group.contains(s.var)) {
                out.add(s.var);
            }
            pushChildren(x, stack);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Contification (plan.md M7-02)

    private record Position(Expr e, boolean tail) {
    }

    /**
     * Marks the loops that are compiled into the root that enters them ({@link Lambda#contified}):
     * the lambda of a {@code letrec*} of one variable whose body calls the variable with as many
     * arguments as the lambda has parameters (what a named {@code let}, a {@code do} or a body with
     * one internal procedure and a call of it is), when
     *
     * <ul>
     * <li>the {@code letrec*} is in tail position in its lambda's body (a root, or a loop in tail
     * position of one), so the loop's tail position is the root's;</li>
     * <li>the variable is never assigned, and every reference to it is a call, in tail position in
     * the lambda's body, with as many arguments: so the procedure is never a value, no other root
     * calls it, and each call is a jump back to the loop's start with new values for its
     * parameters;</li>
     * <li>the lambda's body makes no non-tail call of a procedure it can't know (a variable that
     * isn't a fixed {@code letrec*} procedure, a computed procedure, or one of {@code callers}). Such
     * a call site is the one that goes megamorphic, and a loop that has one stays a procedure of
     * its own, small enough for Truffle to split it for each caller (map's, for-each's, design.md
     * "Calls and tail calls"); merged into the root that enters it, that root would often be past
     * the splitting limit.</li>
     * </ul>
     *
     * A loop in a loop can be contified too: the inner ones are decided first, and a call of the
     * outer loop's variable in tail position of a contified inner one is a jump too.
     */
    private static void contify(Lambda unit, Set<GlobalCell> callers) {
        // The candidates in tail position, in pre-order: one inside another comes after it.
        List<Ast.Letrec> candidates = new ArrayList<>();
        Deque<Position> stack = new ArrayDeque<>();
        stack.push(new Position(unit.body, !unit.program));
        while (!stack.isEmpty()) {
            Position p = stack.pop();
            if (p.tail() && loopShape(p.e()) instanceof Ast.Letrec l) {
                candidates.add(l);
            }
            pushPositions(p.e(), p.tail(), stack);
        }
        for (int i = candidates.size() - 1; i >= 0; i--) {
            Ast.Letrec l = candidates.get(i);
            Lambda fn = (Lambda) l.inits.get(0);
            if (contifiable(l.vars.get(0), fn, (Ast.Call) l.body, callers)) {
                fn.contified = true;
            }
        }
    }

    /** {@code e} if it is a letrec* of one lambda, entered by a call of it with its arity; else null. */
    private static Ast.Letrec loopShape(Expr e) {
        if (e instanceof Ast.Letrec l && l.vars.size() == 1 && l.inits.get(0) instanceof Lambda fn && fn.rest == null && !l.vars.get(0).assigned &&
                        l.body instanceof Ast.Call c && c.procedure instanceof Ast.LocalRef r && r.var == l.vars.get(0) &&
                        c.arguments.size() == fn.params.size()) {
            return l;
        }
        return null;
    }

    private static boolean contifiable(Var v, Lambda fn, Ast.Call entry, Set<GlobalCell> callers) {
        for (Expr argument : entry.arguments) {
            if (mentions(argument, v)) {
                return false;
            }
        }
        int references = 0;
        int jumps = 0;
        Deque<Position> stack = new ArrayDeque<>();
        stack.push(new Position(fn.body, true));
        while (!stack.isEmpty()) {
            Position p = stack.pop();
            Expr e = p.e();
            if (e instanceof Lambda inner) {
                if (inner.contified) {
                    // Entered in tail position here (contify): its tail positions are this loop's.
                    stack.push(new Position(inner.body, true));
                } else if (mentions(inner, v)) {
                    return false;
                }
                continue;
            }
            if (e instanceof Ast.LocalRef r && r.var == v) {
                references++;
            } else if (e instanceof Ast.LocalSet s && s.var == v) {
                return false;
            } else if (e instanceof Ast.Call c) {
                if (p.tail() && c.procedure instanceof Ast.LocalRef r && r.var == v) {
                    if (c.arguments.size() != fn.params.size()) {
                        return false;
                    }
                    jumps++;
                } else if (!p.tail() && !known(c.procedure, callers)) {
                    return false;
                }
            }
            pushPositions(e, p.tail(), stack);
        }
        return references == jumps;
    }

    /** Whether a call of {@code procedure} calls the same procedure every time, or a primitive. */
    private static boolean known(Expr procedure, Set<GlobalCell> callers) {
        return procedure instanceof Ast.GlobalRef g ? !callers.contains(g.cell) : procedure instanceof Ast.LocalRef r && r.var.fixed;
    }

    /** Whether {@code e} refers to {@code v}, in it or in a lambda in it. */
    private static boolean mentions(Expr e, Var v) {
        Deque<Expr> stack = new ArrayDeque<>();
        stack.push(e);
        while (!stack.isEmpty()) {
            Expr x = stack.pop();
            if ((x instanceof Ast.LocalRef r && r.var == v) || (x instanceof Ast.LocalSet s && s.var == v)) {
                return true;
            }
            pushChildren(x, stack);
        }
        return false;
    }

    /**
     * Pushes the subexpressions of {@code e}, with whether each is in tail position in its lambda's
     * body; {@code tail}: whether {@code e} is. A lambda's body is in tail position in it, except a
     * program's (whose calls are never tail calls, CodeGenerator).
     */
    private static void pushPositions(Expr e, boolean tail, Deque<Position> stack) {
        if (e instanceof Ast.If i) {
            stack.push(new Position(i.test, false));
            stack.push(new Position(i.consequent, tail));
            stack.push(new Position(i.alternative, tail));
        } else if (e instanceof Ast.Seq s) {
            for (int k = 0; k < s.body.size(); k++) {
                stack.push(new Position(s.body.get(k), tail && k == s.body.size() - 1));
            }
        } else if (e instanceof Ast.Let l) {
            for (Expr x : l.inits) {
                stack.push(new Position(x, false));
            }
            stack.push(new Position(l.body, tail));
        } else if (e instanceof Ast.Letrec l) {
            for (Expr x : l.inits) {
                stack.push(new Position(x, false));
            }
            stack.push(new Position(l.body, tail));
        } else if (e instanceof Lambda fn) {
            stack.push(new Position(fn.body, !fn.program));
        } else {
            Deque<Expr> children = new ArrayDeque<>();
            pushChildren(e, children);
            for (Expr child : children) {
                stack.push(new Position(child, false));
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Owners, free variables and captures

    private record Item(Expr e, Lambda in) {
    }

    /**
     * Sets each variable's owner (the lambda whose root runs its binding form), each lambda's parent
     * and free variables, and which variables closures capture. Every binding form comes before the
     * references to its variables. A fixed variable referred to in its own lambda, or in a lambda in
     * it, is the running closure of its own lambda's root: no lambda from there out captures it.
     */
    private static void resolve(Lambda unit) {
        Deque<Item> stack = new ArrayDeque<>();
        enter(unit);
        stack.push(new Item(unit.body, unit));
        while (!stack.isEmpty()) {
            Item item = stack.pop();
            Expr e = item.e();
            Lambda in = item.in();
            if (e instanceof Lambda fn && fn.contified) {
                // A loop of the root it is in: its parameters are that root's locals.
                for (Var v : fn.params) {
                    own(v, in);
                }
                stack.push(new Item(fn.body, in));
                continue;
            }
            if (e instanceof Lambda fn) {
                fn.parent = in;
                enter(fn);
                stack.push(new Item(fn.body, fn));
                continue;
            }
            if (e instanceof Ast.LocalRef r) {
                reference(r.var, in);
            } else if (e instanceof Ast.LocalSet s) {
                reference(s.var, in);
            } else if (e instanceof Ast.Let l) {
                for (Var v : l.vars) {
                    own(v, in);
                }
            } else if (e instanceof Ast.Letrec l) {
                for (Var v : l.vars) {
                    own(v, in);
                }
            }
            Deque<Expr> children = new ArrayDeque<>();
            pushChildren(e, children);
            for (Expr child : children) {
                stack.push(new Item(child, in));
            }
        }
    }

    private static void enter(Lambda fn) {
        fn.owned.clear();
        fn.free.clear();
        for (Var v : fn.params) {
            own(v, fn);
        }
        if (fn.rest != null) {
            own(fn.rest, fn);
        }
    }

    private static void own(Var v, Lambda in) {
        v.owner = in;
        v.captured = false;
        in.owned.add(v);
    }

    private static void reference(Var v, Lambda in) {
        for (Lambda l = in; l != v.owner && l != v.self; l = l.parent) {
            v.captured = true;
            l.capture(v);
        }
    }

    // ------------------------------------------------------------------------------------------

    /** Pushes the subexpressions of {@code e}, lambda bodies included. */
    private static void pushChildren(Expr e, Deque<Expr> stack) {
        if (e instanceof Ast.LocalSet s) {
            stack.push(s.value);
        } else if (e instanceof Ast.GlobalSet s) {
            stack.push(s.value);
        } else if (e instanceof Ast.If i) {
            stack.push(i.test);
            stack.push(i.consequent);
            stack.push(i.alternative);
        } else if (e instanceof Ast.Seq s) {
            for (Expr x : s.body) {
                stack.push(x);
            }
        } else if (e instanceof Ast.Call c) {
            stack.push(c.procedure);
            for (Expr x : c.arguments) {
                stack.push(x);
            }
        } else if (e instanceof Ast.Let l) {
            for (Expr x : l.inits) {
                stack.push(x);
            }
            stack.push(l.body);
        } else if (e instanceof Ast.Letrec l) {
            for (Expr x : l.inits) {
                stack.push(x);
            }
            stack.push(l.body);
        } else if (e instanceof Lambda fn) {
            stack.push(fn.body);
        }
        // Const, LocalRef and GlobalRef have none.
    }

    private static <T> Set<T> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
