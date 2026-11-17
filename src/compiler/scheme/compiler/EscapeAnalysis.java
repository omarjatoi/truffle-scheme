package scheme.compiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import scheme.compiler.Ast.Expr;
import scheme.compiler.Ast.Lambda;
import scheme.compiler.Ast.Var;
import scheme.runtime.GlobalCell;

/**
 * Escape-only {@code call/cc} (M5-04, design.md "Continuations"): rewrites
 * {@code (call/cc (lambda (k) body))} to {@code (%call/ec (lambda (k) body))} when k can only be
 * invoked during the receiver's dynamic extent, so invoking it is an escape (an unwind to the
 * {@code %call/ec} frame) rather than the reinstatement of a captured continuation.
 *
 * <p>The analysis decides which values are <em>contained</em>: used only during the extent of the
 * call that binds or makes them (its tail calls included). A value is a variable, or a lambda
 * expression (the closures it makes). k is escape-only when it is contained. A variable is
 * contained when it is never assigned, every reference to it is
 * <ul>
 * <li>the operator of a call, or</li>
 * <li>an argument of a call of a <em>known</em> procedure, with exactly as many arguments as its
 * parameters, whose parameter in that position is contained, or</li>
 * <li>the init of a {@code let} variable that is contained,</li>
 * </ul>
 * and every lambda the reference is in, inside the variable's own root (each lambda whose closures
 * capture the variable), is contained. A lambda is contained when it is the operator of a call, an
 * argument of a call of a known procedure whose parameter in that position is contained, or the
 * init of a {@code let} or {@code letrec*} variable that is contained: a {@code do} or named
 * {@code let} loop, or a local helper, that is only ever called. A lambda anywhere else (stored,
 * returned, or passed to a procedure the analysis can't see, {@code call/cc}'s receiver among them)
 * is not.
 *
 * <p>A known procedure is a lambda whose code the analysis sees at that call: a literal lambda in
 * operator position, an unassigned {@code let} or {@code letrec*} variable (an internal definition)
 * bound to a lambda, or, in a closed unit, a global the unit defines once, to a lambda, and never
 * assigns. A closed unit is an isolated program (design.md D15): no other unit can redefine its
 * globals. Library bodies are not closed, since a macro a library exports can assign its globals
 * elsewhere; neither is the interaction environment. So {@code map}, {@code for-each},
 * {@code apply} and the prelude's other procedures are unknown everywhere.
 *
 * <p>Contained values are a greatest fixed point, so recursion that passes k along (ctak's
 * {@code ctak-aux}, fibc's {@code fibc} and {@code addc}) qualifies, and so does a loop that calls
 * itself and k. It is sound because every use of a contained value happens in the extent of the
 * call that bound or made it: a variable is used by the code of its scope, which runs in its
 * binding call or in a contained closure made in it, so (by induction) in the extent of that call;
 * a contained argument or init is used only through its contained parameter or variable, bound by a
 * call made in the current extent. So every invocation of k happens in a chain of calls from the
 * receiver, before {@code %call/ec} returns: k never outlives the receiver's extent except inside
 * continuations captured within it, and those carry the escape frame along (as a prompt marker)
 * whenever they are re-entered.
 *
 * <p>Runs after {@link Closures}: a contified loop (M7-02) is code of the root that enters it, not a
 * closure, and each lambda's {@link Lambda#parent} and each variable's {@link Var#owner} are the
 * roots that make and bind them. Then {@link Closures#contifyEscapes} contifies the loops whose
 * only unknown non-tail calls are of the escape-only ks found here (M9-05).
 *
 * <p>No Java recursion proportional to nesting depth (AGENTS.md rule 4): an explicit stack.
 */
final class EscapeAnalysis {

    /** A value (a variable or a lambda) as an argument of a call. */
    private record Use(Ast.Call call, int position) {
    }

    /** An expression in the code of root {@code in}; {@code bound}: a lambda whose use is recorded. */
    private record Item(Expr e, Lambda in, boolean bound) {
    }

    private final boolean closed;
    private final Map<Object, List<Use>> uses = new IdentityHashMap<>();
    /** Values that are not contained whatever the others are: used other than above, or assigned. */
    private final Set<Object> unsafe = Collections.newSetFromMap(new IdentityHashMap<>());
    /** The lambda of each let or letrec* variable whose init is one. */
    private final Map<Var, Lambda> boundLambdas = new IdentityHashMap<>();
    private final Map<GlobalCell, Lambda> globalLambdas = new IdentityHashMap<>();
    /** Every definition and assignment of each global in the unit. */
    private final Map<GlobalCell, Integer> stores = new IdentityHashMap<>();
    /** For each value, the values that are contained only if it is. */
    private final Map<Object, List<Object>> dependents = new IdentityHashMap<>();
    /** The call/cc calls with a one-parameter lambda receiver. */
    private final List<Ast.Call> sites = new ArrayList<>();

    private EscapeAnalysis(boolean closed) {
        this.closed = closed;
    }

    /**
     * Rewrites, in place, the escape-only {@code call/cc}s of a unit (a program, library body, eval
     * or load). Returns their k variables.
     */
    static Set<Var> rewrite(Lambda unit, boolean closed, Set<GlobalCell> callCC, GlobalCell callEC) {
        EscapeAnalysis analysis = new EscapeAnalysis(closed);
        analysis.scan(unit, callCC);
        if (analysis.sites.isEmpty()) {
            return Set.of();
        }
        Set<Object> notContained = analysis.solve();
        Set<Var> escapes = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Ast.Call site : analysis.sites) {
            Var k = ((Lambda) site.arguments.get(0)).params.get(0);
            if (!k.assigned && !notContained.contains(k)) {
                Ast.GlobalRef callEc = new Ast.GlobalRef(callEC);
                callEc.source = site.procedure.source;
                site.procedure = callEc;
                escapes.add(k);
            }
        }
        return escapes;
    }

    private void scan(Lambda unit, Set<GlobalCell> callCC) {
        Deque<Item> stack = new ArrayDeque<>();
        stack.push(new Item(unit.body, unit, false));
        while (!stack.isEmpty()) {
            Item item = stack.pop();
            Expr e = item.e();
            Lambda in = item.in();
            if (e instanceof Ast.LocalRef r) {
                reference(r.var, in);
                unsafe.add(r.var);
            } else if (e instanceof Ast.LocalSet s) {
                reference(s.var, in);
                unsafe.add(s.var);
                stack.push(new Item(s.value, in, false));
            } else if (e instanceof Ast.GlobalSet s) {
                stores.merge(s.cell, 1, Integer::sum);
                if (s.define && s.value instanceof Lambda fn) {
                    globalLambdas.put(s.cell, fn);
                }
                stack.push(new Item(s.value, in, false));
            } else if (e instanceof Ast.If i) {
                stack.push(new Item(i.test, in, false));
                stack.push(new Item(i.consequent, in, false));
                stack.push(new Item(i.alternative, in, false));
            } else if (e instanceof Ast.Seq s) {
                for (Expr x : s.body) {
                    stack.push(new Item(x, in, false));
                }
            } else if (e instanceof Ast.Let l) {
                for (int i = 0; i < l.vars.size(); i++) {
                    bind(l.vars.get(i), l.inits.get(i), in, stack);
                }
                stack.push(new Item(l.body, in, false));
            } else if (e instanceof Ast.Letrec l) {
                for (int i = 0; i < l.vars.size(); i++) {
                    bind(l.vars.get(i), l.inits.get(i), in, stack);
                }
                stack.push(new Item(l.body, in, false));
            } else if (e instanceof Lambda fn) {
                if (!item.bound()) {
                    unsafe.add(fn);
                }
                // A contified loop's body is code of the root that enters it.
                stack.push(new Item(fn.body, fn.contified ? in : fn, false));
            } else if (e instanceof Ast.Call c) {
                use(c.procedure, c, -1, in, stack);
                for (int i = 0; i < c.arguments.size(); i++) {
                    use(c.arguments.get(i), c, i, in, stack);
                }
                if (c.procedure instanceof Ast.GlobalRef g && callCC.contains(g.cell) && c.arguments.size() == 1 &&
                                c.arguments.get(0) instanceof Lambda receiver && receiver.params.size() == 1 && receiver.rest == null) {
                    sites.add(c);
                }
            }
            // Const and GlobalRef refer to no variable.
        }
    }

    /** A let or letrec* variable and its init: a variable or lambda there is contained if v is. */
    private void bind(Var v, Expr init, Lambda in, Deque<Item> stack) {
        if (init instanceof Ast.LocalRef r) {
            reference(r.var, in);
            add(dependents, v, r.var);
        } else if (init instanceof Lambda fn) {
            boundLambdas.put(v, fn);
            add(dependents, v, fn);
            stack.push(new Item(fn, in, true));
        } else {
            stack.push(new Item(init, in, false));
        }
    }

    /** A call's operator ({@code position} -1) or argument. */
    private void use(Expr e, Ast.Call call, int position, Lambda in, Deque<Item> stack) {
        if (e instanceof Ast.LocalRef r) {
            reference(r.var, in);
            if (position >= 0) {
                add(uses, r.var, new Use(call, position));
            }
        } else if (e instanceof Lambda fn) {
            if (position >= 0) {
                add(uses, fn, new Use(call, position));
            }
            stack.push(new Item(fn, in, true));
        } else {
            stack.push(new Item(e, in, false));
        }
    }

    /**
     * A reference to {@code v} in the code of root {@code in}: the closures of each lambda from
     * there out to v's own root capture v, so v is contained only if those lambdas are.
     */
    private void reference(Var v, Lambda in) {
        for (Lambda l = in; l != v.owner; l = l.parent) {
            if (l == null) {
                // Not expected: Closures resolved every reference to a root around it.
                unsafe.add(v);
                return;
            }
            add(dependents, l, v);
        }
    }

    private static <K, T> void add(Map<K, List<T>> map, K key, T item) {
        List<T> list = map.get(key);
        if (list == null) {
            list = new ArrayList<>();
            map.put(key, list);
        }
        list.add(item);
    }

    /** The lambda a call's operator is known to evaluate to, or null. */
    private Lambda known(Expr procedure) {
        if (procedure instanceof Lambda fn) {
            return fn;
        }
        if (procedure instanceof Ast.LocalRef r && !r.var.assigned) {
            return boundLambdas.get(r.var);
        }
        if (closed && procedure instanceof Ast.GlobalRef g && stores.getOrDefault(g.cell, 0) == 1) {
            return globalLambdas.get(g.cell);
        }
        return null;
    }

    /**
     * The values that are not contained: the complement of the greatest fixed point, by propagating
     * "not contained" from each value to its dependents. (An assigned parameter is unsafe: its
     * {@code set!} is a reference other than a call.)
     */
    private Set<Object> solve() {
        Set<Object> not = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object> work = new ArrayDeque<>();
        for (Object v : unsafe) {
            if (not.add(v)) {
                work.push(v);
            }
        }
        for (Map.Entry<Object, List<Use>> entry : uses.entrySet()) {
            Object v = entry.getKey();
            for (Use u : entry.getValue()) {
                Lambda m = known(u.call().procedure);
                if (m == null || m.rest != null || m.params.size() != u.call().arguments.size()) {
                    if (not.add(v)) {
                        work.push(v);
                    }
                } else {
                    add(dependents, m.params.get(u.position()), v);
                }
            }
        }
        while (!work.isEmpty()) {
            for (Object v : dependents.getOrDefault(work.pop(), List.of())) {
                if (not.add(v)) {
                    work.push(v);
                }
            }
        }
        return not;
    }
}
