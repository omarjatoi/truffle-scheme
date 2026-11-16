package scheme.compiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.reader.ScopeSet;
import scheme.reader.Syntax;
import scheme.runtime.printer.Printer;
import scheme.runtime.value.SChar;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SString;
import scheme.runtime.value.SSymbol;
import scheme.runtime.value.SVector;

/**
 * A {@code syntax-rules} transformer (R7RS §4.3.2): literals, {@code _}, ellipsis at any depth with
 * subpatterns after it, dotted and vector patterns, a custom ellipsis identifier, and
 * {@code (... template)} escapes.
 *
 * <p>Identifiers in the spec are compared with {@code bound-identifier=?} (same symbol, same scopes)
 * to find literals and pattern variables, so a {@code syntax-rules} that a macro writes keeps the
 * use site's identifiers apart from the macro's own. A literal matches an input identifier that is
 * {@code free-identifier=?} to it. {@code _} and the ellipsis are recognized by name.
 *
 * <p>Hygiene is the expander's: the template's identifiers keep the spec's scopes, and the expander
 * flips the use's introduction scope on the result (Flatt 2016).
 *
 * <p>No Java recursion proportional to the depth of patterns, templates or input (AGENTS.md rule 4):
 * compiling, matching and instantiating each run on an explicit stack.
 */
final class SyntaxRules implements Transformer {

    // ------------------------------------------------------------------------------------------
    // Patterns and templates

    private abstract static class Pat {
    }

    /** A pattern variable, bound at {@code depth} levels of ellipsis. */
    private static final class PVar extends Pat {
        final SSymbol name;
        final int depth;

        PVar(SSymbol name, int depth) {
            this.name = name;
            this.depth = depth;
        }
    }

    private static final class PAny extends Pat {
    }

    private static final class PLiteral extends Pat {
        final Object id;

        PLiteral(Object id) {
            this.id = id;
        }
    }

    /** An atom (or the empty list), matched by value. */
    private static final class PDatum extends Pat {
        final Object datum;

        PDatum(Object datum) {
            this.datum = datum;
        }
    }

    /** A list or vector pattern: {@code before ... [ellipsis <ellipsis>] after ... [. tail]}. */
    private static final class PSeq extends Pat {
        final boolean vector;
        Pat[] before;
        Pat ellipsis;
        Pat[] after;
        /** The dotted tail; for a proper list, the empty list. Unused for vectors. */
        Pat tail;
        /** The variables inside {@link #ellipsis}. */
        final List<PVar> ellipsisVars = new ArrayList<>();

        PSeq(boolean vector) {
            this.vector = vector;
        }
    }

    private abstract static class Tmpl {
    }

    /** Copied as is: an identifier that isn't a pattern variable, or a constant. */
    private static final class TConst extends Tmpl {
        final Object syntax;

        TConst(Object syntax) {
            this.syntax = syntax;
        }
    }

    private static final class TVar extends Tmpl {
        final PVar var;

        TVar(PVar var) {
            this.var = var;
        }
    }

    /** A list or vector template; each element is followed by {@code ellipses[i]} ellipses. */
    private static final class TSeq extends Tmpl {
        final boolean vector;
        final boolean immutable;
        Tmpl[] elements;
        int[] ellipses;
        /** For elements followed by an ellipsis: the pattern variables inside them. */
        List<List<PVar>> varsUnder;
        Tmpl tail;

        TSeq(boolean vector, boolean immutable) {
            this.vector = vector;
            this.immutable = immutable;
        }
    }

    private record Rule(Pat pattern, Tmpl template) {
    }

    private record Key(SSymbol name, ScopeSet scopes) {
    }

    private final Expander expander;
    /** The {@code syntax-rules} form, for {@link PreludeImage}, which compiles it again in each context. */
    private final Object spec;
    private final SSymbol ellipsis;
    /** Whether the spec names its ellipsis; otherwise it is {@code ...} where not bound lexically. */
    private final boolean customEllipsis;
    private final SSymbol underscore;
    private final List<Object> literals = new ArrayList<>();
    private final List<Rule> rules = new ArrayList<>();

    /** Compiles {@code (syntax-rules [ellipsis] (literal ...) (pattern template) ...)}. */
    @TruffleBoundary
    SyntaxRules(Object spec, Expander expander, SSymbol defaultEllipsis, SSymbol underscore) {
        this.expander = expander;
        this.spec = spec;
        this.underscore = underscore;
        List<Object> f = expander.list(spec);
        int at = 1;
        if (f.size() > at && Expander.isIdentifier(f.get(at))) {
            this.ellipsis = Expander.symbol(f.get(at));
            this.customEllipsis = true;
            at++;
        } else {
            this.ellipsis = defaultEllipsis;
            this.customEllipsis = false;
        }
        if (f.size() <= at) {
            throw expander.syntaxError("syntax-rules needs a list of literals", spec);
        }
        for (Object literal : expander.list(f.get(at))) {
            if (!Expander.isIdentifier(literal)) {
                throw expander.syntaxError("syntax-rules literals must be identifiers", spec);
            }
            literals.add(literal);
        }
        for (Object rule : f.subList(at + 1, f.size())) {
            List<Object> r = expander.list(rule);
            if (r.size() != 2 || !(Syntax.e(r.get(0)) instanceof SPair pattern)) {
                throw expander.syntaxError("a syntax-rules rule is (pattern template), with a list pattern", rule);
            }
            // The keyword position is ignored.
            Map<Key, PVar> vars = new HashMap<>();
            Pat p = compilePattern(pattern.cdr, vars, rule);
            rules.add(new Rule(p, compileTemplate(r.get(1), vars, rule)));
        }
    }

    /** The {@code syntax-rules} form. */
    Object spec() {
        return spec;
    }

    /** The expander of the unit that defined this macro. */
    Expander definedBy() {
        return expander;
    }

    private static boolean sameIdentifier(Object a, Object b) {
        return Expander.symbol(a) == Expander.symbol(b) && Expander.scopes(a).equals(Expander.scopes(b));
    }

    private boolean isLiteral(Object id) {
        for (Object literal : literals) {
            if (sameIdentifier(literal, id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The ellipsis identifier: by name, unless it is a literal (R7RS §4.3.2). The default
     * {@code ...} is the ellipsis only where it isn't bound lexically: a {@code ...} that a
     * {@code let} binds is an ordinary identifier, as {@code free-identifier=?} would say.
     */
    private boolean isEllipsis(Object o) {
        return Expander.isIdentifier(o) && Expander.symbol(o) == ellipsis && !isLiteral(o) && (customEllipsis || !expander.isLexicallyBound(o));
    }

    // ------------------------------------------------------------------------------------------
    // Compiling

    private record PTask(Object syntax, List<List<PVar>> chain, Consumer<Pat> sink) {
    }

    private Pat compilePattern(Object root, Map<Key, PVar> vars, Object rule) {
        Pat[] result = new Pat[1];
        Deque<PTask> tasks = new ArrayDeque<>();
        tasks.push(new PTask(root, List.of(), p -> result[0] = p));
        while (!tasks.isEmpty()) {
            PTask t = tasks.pop();
            Object s = t.syntax();
            Object d = Syntax.e(s);
            if (Expander.isIdentifier(s)) {
                if (isLiteral(s)) {
                    t.sink().accept(new PLiteral(s));
                } else if (Expander.symbol(s) == underscore) {
                    t.sink().accept(new PAny());
                } else if (isEllipsis(s)) {
                    throw expander.syntaxError("misplaced ellipsis in pattern", rule);
                } else {
                    PVar v = new PVar(Expander.symbol(s), t.chain().size());
                    if (vars.put(new Key(v.name, Expander.scopes(s)), v) != null) {
                        throw expander.syntaxError("duplicate pattern variable " + v.name, rule);
                    }
                    for (List<PVar> collector : t.chain()) {
                        collector.add(v);
                    }
                    t.sink().accept(v);
                }
            } else if (d instanceof SPair || d instanceof SVector) {
                List<Object> elements = new ArrayList<>();
                Object tail = SConstant.EMPTY_LIST;
                if (d instanceof SVector vec) {
                    elements.addAll(List.of(vec.toArray()));
                } else {
                    Object rest = d;
                    while (rest instanceof SPair p) {
                        elements.add(p.car);
                        tail = p.cdr;
                        rest = Syntax.e(p.cdr);
                    }
                    if (rest == SConstant.EMPTY_LIST) {
                        tail = SConstant.EMPTY_LIST;
                    }
                }
                PSeq seq = new PSeq(d instanceof SVector);
                int dots = -1;
                for (int i = 0; i < elements.size(); i++) {
                    if (isEllipsis(elements.get(i))) {
                        if (dots >= 0 || i == 0) {
                            throw expander.syntaxError("misplaced ellipsis in pattern", rule);
                        }
                        dots = i;
                    }
                }
                List<Object> before = dots < 0 ? elements : elements.subList(0, dots - 1);
                List<Object> after = dots < 0 ? List.of() : elements.subList(dots + 1, elements.size());
                seq.before = new Pat[before.size()];
                seq.after = new Pat[after.size()];
                t.sink().accept(seq);
                for (int i = 0; i < before.size(); i++) {
                    int k = i;
                    tasks.push(new PTask(before.get(i), t.chain(), p -> seq.before[k] = p));
                }
                for (int i = 0; i < after.size(); i++) {
                    int k = i;
                    tasks.push(new PTask(after.get(i), t.chain(), p -> seq.after[k] = p));
                }
                if (dots >= 0) {
                    List<List<PVar>> inner = new ArrayList<>(t.chain());
                    inner.add(seq.ellipsisVars);
                    tasks.push(new PTask(elements.get(dots - 1), inner, p -> seq.ellipsis = p));
                }
                if (!seq.vector) {
                    tasks.push(new PTask(tail, t.chain(), p -> seq.tail = p));
                }
            } else {
                t.sink().accept(new PDatum(d == SConstant.EMPTY_LIST ? d : Syntax.toDatum(s)));
            }
        }
        return result[0];
    }

    private record TTask(Object syntax, boolean ellipsisActive, List<List<PVar>> chain, Consumer<Tmpl> sink) {
    }

    private Tmpl compileTemplate(Object root, Map<Key, PVar> vars, Object rule) {
        Tmpl[] result = new Tmpl[1];
        Deque<TTask> tasks = new ArrayDeque<>();
        tasks.push(new TTask(root, true, List.of(), x -> result[0] = x));
        while (!tasks.isEmpty()) {
            TTask t = tasks.pop();
            Object s = t.syntax();
            Object d = Syntax.e(s);
            if (Expander.isIdentifier(s)) {
                PVar v = vars.get(new Key(Expander.symbol(s), Expander.scopes(s)));
                if (v != null) {
                    for (List<PVar> collector : t.chain()) {
                        collector.add(v);
                    }
                    t.sink().accept(new TVar(v));
                } else if (t.ellipsisActive() && isEllipsis(s)) {
                    throw expander.syntaxError("misplaced ellipsis in template", rule);
                } else {
                    t.sink().accept(new TConst(s));
                }
            } else if (d instanceof SPair p && t.ellipsisActive() && isEllipsis(p.car)) {
                // (... template): ellipses inside have no special meaning.
                if (!(Syntax.e(p.cdr) instanceof SPair q) || Syntax.e(q.cdr) != SConstant.EMPTY_LIST) {
                    throw expander.syntaxError("an ellipsis escape is (... template)", rule);
                }
                tasks.push(new TTask(q.car, false, t.chain(), t.sink()));
            } else if (d instanceof SPair || d instanceof SVector) {
                List<Object> items = new ArrayList<>();
                Object tail = SConstant.EMPTY_LIST;
                boolean immutable = false;
                if (d instanceof SVector vec) {
                    items.addAll(List.of(vec.toArray()));
                    immutable = vec.immutable;
                } else {
                    Object rest = d;
                    while (rest instanceof SPair p) {
                        items.add(p.car);
                        tail = p.cdr;
                        rest = Syntax.e(p.cdr);
                    }
                    if (rest == SConstant.EMPTY_LIST) {
                        tail = SConstant.EMPTY_LIST;
                    }
                }
                List<Object> elements = new ArrayList<>();
                List<Integer> counts = new ArrayList<>();
                for (Object item : items) {
                    if (t.ellipsisActive() && isEllipsis(item)) {
                        if (elements.isEmpty()) {
                            throw expander.syntaxError("misplaced ellipsis in template", rule);
                        }
                        counts.set(counts.size() - 1, counts.get(counts.size() - 1) + 1);
                    } else {
                        elements.add(item);
                        counts.add(0);
                    }
                }
                TSeq seq = new TSeq(d instanceof SVector, immutable);
                seq.elements = new Tmpl[elements.size()];
                seq.ellipses = new int[elements.size()];
                seq.varsUnder = new ArrayList<>();
                t.sink().accept(seq);
                for (int i = 0; i < elements.size(); i++) {
                    int k = i;
                    seq.ellipses[i] = counts.get(i);
                    List<List<PVar>> chain = t.chain();
                    List<PVar> under = new ArrayList<>();
                    seq.varsUnder.add(under);
                    if (seq.ellipses[i] > 0) {
                        chain = new ArrayList<>(chain);
                        chain.add(under);
                    }
                    tasks.push(new TTask(elements.get(i), t.ellipsisActive(), chain, x -> seq.elements[k] = x));
                }
                if (!seq.vector) {
                    tasks.push(new TTask(tail, t.ellipsisActive(), t.chain(), x -> seq.tail = x));
                }
            } else {
                t.sink().accept(new TConst(s));
            }
        }
        return result[0];
    }

    // ------------------------------------------------------------------------------------------
    // Matching

    /** The matches of a variable under an ellipsis, one per repetition. */
    private record Seq(List<Object> items) {
    }

    @Override
    @TruffleBoundary
    public Object transform(Object form, Expander ex) {
        Object input = ((SPair) Syntax.e(form)).cdr;
        for (Rule rule : rules) {
            Map<PVar, Object> bindings = match(rule.pattern(), input, ex);
            if (bindings != null) {
                return instantiate(rule.template(), bindings);
            }
        }
        throw ex.syntaxError("no syntax-rules pattern matches", form);
    }

    private record MTask(Pat pat, Object input, Map<PVar, Object> env) {
    }

    private record Collect(List<PVar> vars, List<Map<PVar, Object>> repetitions, Map<PVar, Object> env) {
    }

    /** The pattern variables' matches, or null if the input doesn't match. */
    private static Map<PVar, Object> match(Pat root, Object rootInput, Expander ex) {
        Map<PVar, Object> top = new HashMap<>();
        Deque<Object> tasks = new ArrayDeque<>();
        tasks.push(new MTask(root, rootInput, top));
        while (!tasks.isEmpty()) {
            Object task = tasks.pop();
            if (task instanceof Collect c) {
                for (PVar v : c.vars()) {
                    List<Object> items = new ArrayList<>(c.repetitions().size());
                    for (Map<PVar, Object> rep : c.repetitions()) {
                        items.add(rep.get(v));
                    }
                    c.env().put(v, new Seq(items));
                }
                continue;
            }
            MTask t = (MTask) task;
            Pat p = t.pat();
            Object in = t.input();
            if (p instanceof PVar v) {
                t.env().put(v, in);
            } else if (p instanceof PLiteral l) {
                if (!Expander.isIdentifier(in) || !ex.freeIdentifierEquals(in, l.id)) {
                    return null;
                }
            } else if (p instanceof PDatum d) {
                if (!datumEquals(d.datum, Syntax.e(in))) {
                    return null;
                }
            } else if (p instanceof PSeq seq) {
                if (!matchSeq(seq, in, t.env(), tasks)) {
                    return null;
                }
            }
        }
        return top;
    }

    private static boolean matchSeq(PSeq seq, Object in, Map<PVar, Object> env, Deque<Object> tasks) {
        List<Object> items = new ArrayList<>();
        /* rests.get(i): what remains after i elements (the tail, after the last). */
        List<Object> rests = new ArrayList<>();
        Object d = Syntax.e(in);
        if (seq.vector) {
            if (!(d instanceof SVector vec)) {
                return false;
            }
            items.addAll(List.of(vec.toArray()));
        } else {
            Object rest = in;
            while (Syntax.e(rest) instanceof SPair p) {
                rests.add(rest);
                items.add(p.car);
                rest = p.cdr;
            }
            rests.add(rest);
        }
        int fixed = seq.before.length + seq.after.length;
        if (seq.ellipsis == null ? items.size() < seq.before.length || seq.vector && items.size() != fixed : items.size() < fixed) {
            return false;
        }
        for (int i = 0; i < seq.before.length; i++) {
            tasks.push(new MTask(seq.before[i], items.get(i), env));
        }
        if (seq.ellipsis == null) {
            if (!seq.vector) {
                tasks.push(new MTask(seq.tail, rests.get(seq.before.length), env));
            }
            return true;
        }
        int n = items.size() - fixed;
        for (int i = 0; i < seq.after.length; i++) {
            tasks.push(new MTask(seq.after[i], items.get(seq.before.length + n + i), env));
        }
        if (!seq.vector) {
            tasks.push(new MTask(seq.tail, rests.get(items.size()), env));
        }
        List<Map<PVar, Object>> repetitions = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            repetitions.add(new HashMap<>());
        }
        // Collect runs after the repetitions' matches, which are pushed above it.
        tasks.push(new Collect(seq.ellipsisVars, repetitions, env));
        for (int i = 0; i < n; i++) {
            tasks.push(new MTask(seq.ellipsis, items.get(seq.before.length + i), repetitions.get(i)));
        }
        return true;
    }

    private static boolean datumEquals(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a instanceof Long || a instanceof Double || a instanceof Boolean) {
            return a.equals(b);
        }
        if (a instanceof SChar x) {
            return b instanceof SChar y && x.codePoint == y.codePoint;
        }
        if (a instanceof SString) {
            return b instanceof SString && Printer.print(a, Printer.Mode.WRITE).equals(Printer.print(b, Printer.Mode.WRITE));
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------
    // Instantiating

    /** Pattern variable matches, layered: each ellipsis repetition binds its own on top. */
    private static final class Env {
        final Env parent;
        final Map<PVar, Object> values;

        Env(Env parent, Map<PVar, Object> values) {
            this.parent = parent;
            this.values = values;
        }

        Object get(PVar v) {
            for (Env e = this; e != null; e = e.parent) {
                Object value = e.values.get(v);
                if (value != null) {
                    return value;
                }
            }
            throw new IllegalStateException("unbound pattern variable " + v.name);
        }
    }

    private record ITask(Tmpl tmpl, Env env, Consumer<Object> sink) {
    }

    private Object instantiate(Tmpl root, Map<PVar, Object> bindings) {
        Object[] result = new Object[1];
        Deque<ITask> tasks = new ArrayDeque<>();
        tasks.push(new ITask(root, new Env(null, bindings), o -> result[0] = o));
        while (!tasks.isEmpty()) {
            ITask t = tasks.pop();
            if (t.tmpl() instanceof TConst c) {
                t.sink().accept(c.syntax);
            } else if (t.tmpl() instanceof TVar v) {
                Object value = t.env().get(v.var);
                if (value instanceof Seq) {
                    throw expander.syntaxError("pattern variable " + v.var.name + " needs " + v.var.depth + " ellipses in the template", Syntax.toDatum(v.var.name));
                }
                t.sink().accept(value);
            } else {
                TSeq seq = (TSeq) t.tmpl();
                List<Tmpl> parts = new ArrayList<>();
                List<Env> envs = new ArrayList<>();
                for (int i = 0; i < seq.elements.length; i++) {
                    if (seq.ellipses[i] == 0) {
                        parts.add(seq.elements[i]);
                        envs.add(t.env());
                    } else {
                        for (Env e : repetitions(seq.varsUnder.get(i), t.env(), seq.ellipses[i])) {
                            parts.add(seq.elements[i]);
                            envs.add(e);
                        }
                    }
                }
                if (seq.vector) {
                    Object[] elements = new Object[parts.size()];
                    t.sink().accept(SVector.of(elements, seq.immutable));
                    for (int i = 0; i < parts.size(); i++) {
                        int k = i;
                        tasks.push(new ITask(parts.get(i), envs.get(i), o -> elements[k] = o));
                    }
                } else if (parts.isEmpty()) {
                    tasks.push(new ITask(seq.tail, t.env(), t.sink()));
                } else {
                    SPair head = new SPair(null, null);
                    SPair last = head;
                    tasks.push(new ITask(parts.get(0), envs.get(0), o -> head.car = o));
                    for (int i = 1; i < parts.size(); i++) {
                        SPair next = new SPair(null, null);
                        last.cdr = next;
                        last = next;
                        SPair cell = next;
                        tasks.push(new ITask(parts.get(i), envs.get(i), o -> cell.car = o));
                    }
                    SPair end = last;
                    tasks.push(new ITask(seq.tail, t.env(), o -> end.cdr = o));
                    t.sink().accept(head);
                }
            }
        }
        return result[0];
    }

    /** The environments of {@code levels} nested repetitions over the sequence variables in {@code vars}. */
    private List<Env> repetitions(List<PVar> vars, Env env, int levels) {
        List<Env> envs = List.of(env);
        for (int level = 0; level < levels; level++) {
            List<Env> next = new ArrayList<>();
            for (Env e : envs) {
                int n = -1;
                List<PVar> iterated = new ArrayList<>();
                for (PVar v : vars) {
                    if (e.get(v) instanceof Seq s && !iterated.contains(v)) {
                        if (n >= 0 && s.items().size() != n) {
                            throw expander.syntaxError("pattern variables under one ellipsis matched different numbers of forms", Syntax.toDatum(v.name));
                        }
                        n = s.items().size();
                        iterated.add(v);
                    }
                }
                if (n < 0) {
                    throw expander.syntaxError("no pattern variable to repeat under an ellipsis in the template", SConstant.EMPTY_LIST);
                }
                for (int k = 0; k < n; k++) {
                    Map<PVar, Object> values = new HashMap<>();
                    for (PVar v : iterated) {
                        values.put(v, ((Seq) e.get(v)).items().get(k));
                    }
                    next.add(new Env(e, values));
                }
            }
            envs = next;
        }
        return envs;
    }
}
