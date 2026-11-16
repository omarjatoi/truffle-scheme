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
 * <p>k is <em>escape-only</em> when it is never assigned, no nested lambda refers to it, and every
 * reference to it is
 * <ul>
 * <li>the operator of a call, or</li>
 * <li>an argument of a call of a <em>known</em> procedure, with exactly as many arguments as its
 * parameters, whose parameter in that position is escape-only too, or</li>
 * <li>the init of a {@code let} variable ({@link Ast.Let}, the call of a literal lambda) that is
 * escape-only too.</li>
 * </ul>
 * A known procedure is a lambda whose code the analysis sees at that call: a literal lambda in
 * operator position, an unassigned {@code letrec*} variable (an internal definition) bound to a
 * lambda, or, in a closed unit, a global the unit defines once, to a lambda, and never assigns. A
 * closed unit is an isolated program (design.md D15): no other code can redefine its globals.
 * Library bodies are not closed, since a macro a library exports can assign its globals elsewhere;
 * neither is the interaction environment.
 *
 * <p>Escape-only parameters are a greatest fixed point, so recursion that passes k along (ctak's
 * {@code ctak-aux}, fibc's {@code fibc} and {@code addc}) qualifies. Every place an escape-only k
 * can reach is a frame on a chain of direct calls from the receiver: k never outlives the
 * receiver's extent except inside continuations captured within it, and those carry the escape
 * frame along (as a prompt marker) whenever they are re-entered.
 *
 * <p>No Java recursion proportional to nesting depth (AGENTS.md rule 4): an explicit stack.
 */
final class EscapeAnalysis {

    /** A reference to a variable as a call's operator ({@code position} -1) or argument. */
    private record Use(Ast.Call call, int position) {
    }

    private final boolean closed;
    private final Map<Var, List<Use>> uses = new IdentityHashMap<>();
    /** Variables referenced other than as an operator or an argument. */
    private final Set<Var> unsafe = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Var, Lambda> letrecLambdas = new IdentityHashMap<>();
    private final Map<GlobalCell, Lambda> globalLambdas = new IdentityHashMap<>();
    /** Every definition and assignment of each global in the unit. */
    private final Map<GlobalCell, Integer> stores = new IdentityHashMap<>();
    /** Each let variable whose init is a reference to a variable: the variable it is bound to. */
    private final Map<Var, Var> letBindings = new IdentityHashMap<>();
    /** The call/cc calls with a one-parameter lambda receiver. */
    private final List<Ast.Call> sites = new ArrayList<>();

    private EscapeAnalysis(boolean closed) {
        this.closed = closed;
    }

    /** Rewrites, in place, the escape-only {@code call/cc}s of a unit (a program, library body, eval or load). */
    static void rewrite(Lambda unit, boolean closed, Set<GlobalCell> callCC, GlobalCell callEC) {
        EscapeAnalysis analysis = new EscapeAnalysis(closed);
        analysis.scan(unit, callCC);
        if (analysis.sites.isEmpty()) {
            return;
        }
        Set<Var> notEscapeOnly = analysis.solve();
        for (Ast.Call site : analysis.sites) {
            Var k = ((Lambda) site.arguments.get(0)).params.get(0);
            if (escapeOnly(k, notEscapeOnly)) {
                Ast.GlobalRef callEc = new Ast.GlobalRef(callEC);
                callEc.source = site.procedure.source;
                site.procedure = callEc;
            }
        }
    }

    private static boolean escapeOnly(Var v, Set<Var> notEscapeOnly) {
        return !v.assigned && !v.captured && !notEscapeOnly.contains(v);
    }

    private void scan(Lambda unit, Set<GlobalCell> callCC) {
        Deque<Expr> stack = new ArrayDeque<>();
        stack.push(unit.body);
        while (!stack.isEmpty()) {
            Expr e = stack.pop();
            if (e instanceof Ast.LocalRef r) {
                unsafe.add(r.var);
            } else if (e instanceof Ast.LocalSet s) {
                stack.push(s.value);
            } else if (e instanceof Ast.GlobalSet s) {
                stores.merge(s.cell, 1, Integer::sum);
                if (s.define && s.value instanceof Lambda fn) {
                    globalLambdas.put(s.cell, fn);
                }
                stack.push(s.value);
            } else if (e instanceof Ast.If i) {
                stack.push(i.test);
                stack.push(i.consequent);
                stack.push(i.alternative);
            } else if (e instanceof Ast.Seq s) {
                for (Expr x : s.body) {
                    stack.push(x);
                }
            } else if (e instanceof Ast.Let l) {
                for (int i = 0; i < l.vars.size(); i++) {
                    if (l.inits.get(i) instanceof Ast.LocalRef r) {
                        letBindings.put(l.vars.get(i), r.var);
                    } else {
                        stack.push(l.inits.get(i));
                    }
                }
                stack.push(l.body);
            } else if (e instanceof Ast.Letrec l) {
                for (int i = 0; i < l.vars.size(); i++) {
                    if (l.inits.get(i) instanceof Lambda fn) {
                        letrecLambdas.put(l.vars.get(i), fn);
                    }
                    stack.push(l.inits.get(i));
                }
                stack.push(l.body);
            } else if (e instanceof Lambda fn) {
                stack.push(fn.body);
            } else if (e instanceof Ast.Call c) {
                use(c.procedure, c, -1, stack);
                for (int i = 0; i < c.arguments.size(); i++) {
                    use(c.arguments.get(i), c, i, stack);
                }
                if (c.procedure instanceof Ast.GlobalRef g && callCC.contains(g.cell) && c.arguments.size() == 1 &&
                                c.arguments.get(0) instanceof Lambda receiver && receiver.params.size() == 1 && receiver.rest == null) {
                    sites.add(c);
                }
            }
            // Const and GlobalRef refer to no variable.
        }
    }

    private void use(Expr e, Ast.Call call, int position, Deque<Expr> stack) {
        if (e instanceof Ast.LocalRef r) {
            add(uses, r.var, new Use(call, position));
        } else {
            stack.push(e);
        }
    }

    private static <T> void add(Map<Var, List<T>> map, Var key, T item) {
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
        if (procedure instanceof Ast.LocalRef r && r.var.letrec && !r.var.assigned) {
            return letrecLambdas.get(r.var);
        }
        if (closed && procedure instanceof Ast.GlobalRef g && stores.getOrDefault(g.cell, 0) == 1) {
            return globalLambdas.get(g.cell);
        }
        return null;
    }

    /**
     * The variables that are not escape-only: the greatest fixed point, by propagating "not
     * escape-only" from each parameter to the variables passed to it.
     */
    private Set<Var> solve() {
        Set<Var> not = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Var, List<Var>> passedTo = new IdentityHashMap<>();
        Deque<Var> work = new ArrayDeque<>();
        for (Var v : unsafe) {
            if (not.add(v)) {
                work.push(v);
            }
        }
        for (Map.Entry<Var, List<Use>> entry : uses.entrySet()) {
            Var v = entry.getKey();
            for (Use u : entry.getValue()) {
                if (u.position() < 0) {
                    continue;
                }
                Lambda m = known(u.call().procedure);
                if (m == null || m.rest != null || m.params.size() != u.call().arguments.size() || m.params.get(u.position()).assigned ||
                                m.params.get(u.position()).captured) {
                    if (not.add(v)) {
                        work.push(v);
                    }
                } else {
                    add(passedTo, m.params.get(u.position()), v);
                }
            }
        }
        for (Map.Entry<Var, Var> entry : letBindings.entrySet()) {
            Var param = entry.getKey();
            Var v = entry.getValue();
            if (param.assigned || param.captured) {
                if (not.add(v)) {
                    work.push(v);
                }
            } else {
                add(passedTo, param, v);
            }
        }
        while (!work.isEmpty()) {
            for (Var v : passedTo.getOrDefault(work.pop(), List.of())) {
                if (not.add(v)) {
                    work.push(v);
                }
            }
        }
        return not;
    }
}
