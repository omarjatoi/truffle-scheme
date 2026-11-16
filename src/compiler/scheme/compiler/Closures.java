package scheme.compiler;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;

import scheme.compiler.Ast.Expr;
import scheme.compiler.Ast.Lambda;
import scheme.compiler.Ast.Var;

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
 * <p>No Java recursion proportional to nesting depth (AGENTS.md rule 4): explicit stacks.
 */
final class Closures {

    private Closures() {
    }

    static void analyze(Lambda unit) {
        fix(unit);
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
