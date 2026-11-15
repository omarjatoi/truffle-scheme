package scheme.compiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.compiler.Ast.Expr;
import scheme.compiler.Ast.Lambda;
import scheme.compiler.Ast.Var;
import scheme.runtime.GlobalEnvironment;
import scheme.runtime.SchemeError;
import scheme.runtime.printer.Printer;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SSymbol;
import scheme.runtime.value.SymbolTable;

/**
 * Turns data (from the reader) into the core AST (design.md D3): resolves variables, turns internal
 * definitions into {@code letrec*}, and records what closure and assignment conversion need (D2):
 * each lambda's free variables, and which variables are assigned or captured.
 *
 * <p>Only the core forms exist until the expander (M3): quote, lambda, if, set!, define, begin,
 * letrec*, and {@code import} at the start of a program (ignored until libraries exist).
 *
 * <p>No Java recursion proportional to nesting depth (AGENTS.md rule 4): a work loop drives frames,
 * one per form being analyzed.
 */
final class Analyzer {

    private final GlobalEnvironment globals;
    private final SSymbol quote;
    private final SSymbol ifSym;
    private final SSymbol define;
    private final SSymbol set;
    private final SSymbol lambda;
    private final SSymbol begin;
    private final SSymbol letrecStar;
    private final SSymbol importSym;
    private final Set<SSymbol> laterKeywords;

    Analyzer(SymbolTable symbols, GlobalEnvironment globals) {
        this.globals = globals;
        this.quote = symbols.intern("quote");
        this.ifSym = symbols.intern("if");
        this.define = symbols.intern("define");
        this.set = symbols.intern("set!");
        this.lambda = symbols.intern("lambda");
        this.begin = symbols.intern("begin");
        this.letrecStar = symbols.intern("letrec*");
        this.importSym = symbols.intern("import");
        this.laterKeywords = Set.of(symbols.intern("define-syntax"), symbols.intern("let-syntax"), symbols.intern("letrec-syntax"),
                        symbols.intern("syntax-rules"), symbols.intern("include"), symbols.intern("include-ci"), symbols.intern("cond-expand"),
                        symbols.intern("define-library"));
    }

    // ------------------------------------------------------------------------------------------
    // Scopes and errors

    private static final class Scope {
        final Map<SSymbol, Var> vars = new HashMap<>();
        final Scope parent;
        final Lambda lambda;

        Scope(Scope parent, Lambda lambda) {
            this.parent = parent;
            this.lambda = lambda;
        }

        Var lookup(SSymbol name) {
            for (Scope s = this; s != null; s = s.parent) {
                Var v = s.vars.get(name);
                if (v != null) {
                    return v;
                }
            }
            return null;
        }
    }

    @TruffleBoundary
    private static SchemeError syntax(String message, Object form) {
        return new SchemeError("syntax error: " + message + ": " + Printer.print(form, Printer.Mode.WRITE));
    }

    private Expr reference(SSymbol name, Scope scope) {
        Var v = scope.lookup(name);
        if (v == null) {
            return new Ast.GlobalRef(globals.cell(name));
        }
        if (v.owner != scope.lambda) {
            v.captured = true;
            for (Lambda l = scope.lambda; l != v.owner; l = l.parent) {
                l.capture(v);
            }
        }
        return new Ast.LocalRef(v);
    }

    private static boolean isKeyword(Object head, SSymbol keyword, Scope scope) {
        return head == keyword && scope.lookup(keyword) == null;
    }

    private static boolean isForm(Object datum, SSymbol keyword, Scope scope) {
        return datum instanceof SPair p && isKeyword(p.car, keyword, scope);
    }

    /** The elements of a proper list, or a syntax error. */
    private static List<Object> list(Object datum, Object form) {
        List<Object> items = new ArrayList<>();
        Object rest = datum;
        while (rest instanceof SPair p) {
            items.add(p.car);
            rest = p.cdr;
        }
        if (rest != SConstant.EMPTY_LIST) {
            throw syntax("not a proper list", form);
        }
        return items;
    }

    // ------------------------------------------------------------------------------------------
    // The work loop

    private static final Object DONE = new Object();

    /** A datum to analyze in a scope. {@code name} names a lambda (from its definition). */
    private record Analyze(Object datum, Scope scope, String name) {
    }

    private abstract static class Frame {
        /** An {@link Analyze}, a {@link Frame} to run, or {@link #DONE}. */
        abstract Object next();

        abstract void accept(Expr e);

        abstract Expr finish();
    }

    private Expr run(Frame root) {
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(root);
        Expr delivered = null;
        while (true) {
            Frame top = stack.peek();
            if (delivered != null) {
                top.accept(delivered);
                delivered = null;
            }
            Object step = top.next();
            if (step == DONE) {
                stack.pop();
                Expr e = top.finish();
                if (stack.isEmpty()) {
                    return e;
                }
                delivered = e;
            } else if (step instanceof Frame f) {
                stack.push(f);
            } else {
                Analyze a = (Analyze) step;
                Object result = analyze(a);
                if (result instanceof Frame f) {
                    stack.push(f);
                } else {
                    delivered = (Expr) result;
                }
            }
        }
    }

    Lambda analyzeProgram(List<Object> forms) {
        Lambda program = new Lambda(null, true);
        return (Lambda) run(new ProgramFrame(forms, new Scope(null, program)));
    }

    /** One step: an Expr for simple forms, or a Frame for compound ones. */
    private Object analyze(Analyze a) {
        Object d = a.datum();
        Scope scope = a.scope();
        if (d instanceof SSymbol s) {
            return reference(s, scope);
        }
        if (!(d instanceof SPair p)) {
            if (d == SConstant.EMPTY_LIST) {
                throw syntax("() is not an expression", d);
            }
            return new Ast.Const(d);
        }
        Object head = p.car;
        if (head instanceof SSymbol h && scope.lookup(h) == null) {
            if (h == quote) {
                List<Object> f = list(d, d);
                if (f.size() != 2) {
                    throw syntax("quote takes one datum", d);
                }
                return new Ast.Const(f.get(1));
            } else if (h == ifSym) {
                List<Object> f = list(d, d);
                if (f.size() != 3 && f.size() != 4) {
                    throw syntax("if takes 2 or 3 subforms", d);
                }
                return new SeqLikeFrame(f.subList(1, f.size()), scope, SeqLikeFrame.IF);
            } else if (h == set) {
                List<Object> f = list(d, d);
                if (f.size() != 3 || !(f.get(1) instanceof SSymbol target)) {
                    throw syntax("set! takes a variable and an expression", d);
                }
                Expr ref = reference(target, scope);
                return new SetFrame(ref, f.get(2), scope);
            } else if (h == lambda) {
                List<Object> f = list(d, d);
                if (f.size() < 3) {
                    throw syntax("lambda needs formals and a body", d);
                }
                return new LambdaFrame(f.get(1), f.subList(2, f.size()), scope, a.name(), d);
            } else if (h == begin) {
                List<Object> f = list(d, d);
                if (f.size() < 2) {
                    throw syntax("begin needs at least one expression here", d);
                }
                return new SeqLikeFrame(f.subList(1, f.size()), scope, SeqLikeFrame.SEQ);
            } else if (h == letrecStar) {
                return letrec(d, scope);
            } else if (h == define) {
                throw syntax("define is only allowed at the top level or at the start of a body", d);
            } else if (h == importSym) {
                throw syntax("import is only allowed at the start of a program", d);
            } else if (laterKeywords.contains(h)) {
                throw syntax("not supported until the expander (M3)", d);
            }
        }
        return new SeqLikeFrame(list(d, d), scope, SeqLikeFrame.CALL);
    }

    // ------------------------------------------------------------------------------------------
    // Frames

    /** if, begin (as an expression), and procedure calls: analyze every subform in order. */
    private static final class SeqLikeFrame extends Frame {
        static final int IF = 0;
        static final int SEQ = 1;
        static final int CALL = 2;

        final List<Object> forms;
        final Scope scope;
        final int kind;
        final List<Expr> done = new ArrayList<>();
        int index;

        SeqLikeFrame(List<Object> forms, Scope scope, int kind) {
            this.forms = forms;
            this.scope = scope;
            this.kind = kind;
        }

        @Override
        Object next() {
            return index < forms.size() ? new Analyze(forms.get(index++), scope, null) : DONE;
        }

        @Override
        void accept(Expr e) {
            done.add(e);
        }

        @Override
        Expr finish() {
            return switch (kind) {
                case IF -> new Ast.If(done.get(0), done.get(1), done.size() == 3 ? done.get(2) : new Ast.Const(SConstant.UNSPECIFIED));
                case SEQ -> done.size() == 1 ? done.get(0) : new Ast.Seq(done);
                default -> new Ast.Call(done.get(0), done.subList(1, done.size()));
            };
        }
    }

    private static final class SetFrame extends Frame {
        final Expr target;
        final Object value;
        final Scope scope;
        boolean started;
        Expr result;

        SetFrame(Expr target, Object value, Scope scope) {
            this.target = target;
            this.value = value;
            this.scope = scope;
            if (target instanceof Ast.LocalRef ref) {
                ref.var.assigned = true;
            }
        }

        @Override
        Object next() {
            if (started) {
                return DONE;
            }
            started = true;
            return new Analyze(value, scope, null);
        }

        @Override
        void accept(Expr e) {
            result = e;
        }

        @Override
        Expr finish() {
            if (target instanceof Ast.LocalRef ref) {
                return new Ast.LocalSet(ref.var, result);
            }
            return new Ast.GlobalSet(((Ast.GlobalRef) target).cell, result, false);
        }
    }

    private final class LambdaFrame extends Frame {
        final Lambda fn;
        final Scope scope;
        final List<Object> body;
        boolean started;

        LambdaFrame(Object formals, List<Object> body, Scope outer, String name, Object form) {
            this.fn = new Lambda(outer.lambda, false);
            this.fn.name = name;
            this.scope = new Scope(outer, fn);
            this.body = body;
            Object rest = formals;
            while (rest instanceof SPair p) {
                fn.params.add(bindFresh(p.car, form));
                rest = p.cdr;
            }
            if (rest != SConstant.EMPTY_LIST) {
                fn.rest = bindFresh(rest, form);
            }
        }

        private Var bindFresh(Object name, Object form) {
            if (!(name instanceof SSymbol s) || scope.vars.containsKey(s)) {
                throw syntax("invalid or duplicate parameter " + Printer.print(name, Printer.Mode.WRITE), form);
            }
            Var v = fn.bind(s, false);
            scope.vars.put(s, v);
            return v;
        }

        @Override
        Object next() {
            if (started) {
                return DONE;
            }
            started = true;
            return new BodyFrame(body, scope);
        }

        @Override
        void accept(Expr e) {
            fn.body = e;
        }

        @Override
        Expr finish() {
            return fn;
        }
    }

    /** A body: internal definitions (begin splices) then at least one expression, as letrec*. */
    private final class BodyFrame extends Frame {
        final Scope scope;
        final List<Var> vars = new ArrayList<>();
        final List<Object> inits = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        final List<Object> exprs = new ArrayList<>();
        final List<Expr> initExprs = new ArrayList<>();
        final List<Expr> bodyExprs = new ArrayList<>();
        int index;

        BodyFrame(List<Object> body, Scope outer) {
            Deque<Object> queue = new ArrayDeque<>(body);
            List<Object[]> defines = new ArrayList<>();
            while (!queue.isEmpty()) {
                Object d = queue.peekFirst();
                if (exprs.isEmpty() && isForm(d, begin, outer)) {
                    queue.pollFirst();
                    List<Object> inner = list(d, d);
                    for (int i = inner.size() - 1; i >= 1; i--) {
                        queue.addFirst(inner.get(i));
                    }
                } else if (exprs.isEmpty() && isForm(d, define, outer)) {
                    defines.add(parseDefine(queue.pollFirst()));
                } else {
                    exprs.add(queue.pollFirst());
                }
            }
            if (exprs.isEmpty()) {
                throw syntax("a body needs at least one expression", SConstant.EMPTY_LIST);
            }
            this.scope = defines.isEmpty() ? outer : new Scope(outer, outer.lambda);
            for (Object[] def : defines) {
                SSymbol name = (SSymbol) def[0];
                if (scope.vars.containsKey(name)) {
                    throw syntax("duplicate definition", name);
                }
                Var v = outer.lambda.bind(name, true);
                scope.vars.put(name, v);
                vars.add(v);
                inits.add(def[1]);
                names.add(name.toString());
            }
        }

        @Override
        Object next() {
            if (index < inits.size()) {
                int i = index++;
                return new Analyze(inits.get(i), scope, names.get(i));
            }
            int i = index++ - inits.size();
            return i < exprs.size() ? new Analyze(exprs.get(i), scope, null) : DONE;
        }

        @Override
        void accept(Expr e) {
            if (initExprs.size() < inits.size()) {
                initExprs.add(e);
            } else {
                bodyExprs.add(e);
            }
        }

        @Override
        Expr finish() {
            Expr body = bodyExprs.size() == 1 ? bodyExprs.get(0) : new Ast.Seq(bodyExprs);
            return vars.isEmpty() ? body : new Ast.Letrec(vars, initExprs, body);
        }
    }

    /** (letrec* ((var init) ...) body ...). */
    private Frame letrec(Object form, Scope outer) {
        List<Object> f = list(form, form);
        if (f.size() < 3) {
            throw syntax("letrec* needs bindings and a body", form);
        }
        Scope scope = new Scope(outer, outer.lambda);
        List<Var> vars = new ArrayList<>();
        List<Object> inits = new ArrayList<>();
        for (Object binding : list(f.get(1), form)) {
            List<Object> b = list(binding, form);
            if (b.size() != 2 || !(b.get(0) instanceof SSymbol name) || scope.vars.containsKey(name)) {
                throw syntax("invalid letrec* binding", binding);
            }
            Var v = outer.lambda.bind(name, true);
            scope.vars.put(name, v);
            vars.add(v);
            inits.add(b.get(1));
        }
        List<Object> body = f.subList(2, f.size());
        return new Frame() {
            final List<Expr> initExprs = new ArrayList<>();
            Expr bodyExpr;
            int index;

            @Override
            Object next() {
                if (index < inits.size()) {
                    int i = index++;
                    return new Analyze(inits.get(i), scope, vars.get(i).name.toString());
                }
                if (index++ == inits.size()) {
                    return new BodyFrame(body, scope);
                }
                return DONE;
            }

            @Override
            void accept(Expr e) {
                if (initExprs.size() < inits.size()) {
                    initExprs.add(e);
                } else {
                    bodyExpr = e;
                }
            }

            @Override
            Expr finish() {
                return new Ast.Letrec(vars, initExprs, bodyExpr);
            }
        };
    }

    /** {name, value datum} for (define name expr) and (define (name . formals) body ...). */
    private Object[] parseDefine(Object form) {
        List<Object> f = list(form, form);
        if (f.size() < 2) {
            throw syntax("invalid define", form);
        }
        Object target = f.get(1);
        if (target instanceof SSymbol name) {
            if (f.size() != 3) {
                throw syntax("define takes a variable and an expression", form);
            }
            return new Object[]{name, f.get(2)};
        }
        if (target instanceof SPair p && p.car instanceof SSymbol name && f.size() >= 3) {
            // (define (name . formals) body ...) is (define name (lambda formals body ...))
            Object body = ((SPair) ((SPair) form).cdr).cdr;
            return new Object[]{name, new SPair(lambda, new SPair(p.cdr, body))};
        }
        throw syntax("invalid define", form);
    }

    /** The program: imports (ignored until M3), top-level definitions into global cells, and expressions. */
    private final class ProgramFrame extends Frame {
        final Deque<Object> queue;
        final Scope scope;
        final List<Expr> body = new ArrayList<>();
        Ast.GlobalSet pendingDefine;
        boolean leadingImports = true;

        ProgramFrame(List<Object> forms, Scope scope) {
            this.queue = new ArrayDeque<>(forms);
            this.scope = scope;
        }

        @Override
        Object next() {
            while (!queue.isEmpty()) {
                Object d = queue.pollFirst();
                if (isForm(d, importSym, scope)) {
                    if (!leadingImports) {
                        throw syntax("import is only allowed at the start of a program", d);
                    }
                    continue;
                }
                leadingImports = false;
                if (isForm(d, begin, scope)) {
                    List<Object> inner = list(d, d);
                    for (int i = inner.size() - 1; i >= 1; i--) {
                        queue.addFirst(inner.get(i));
                    }
                    continue;
                }
                if (isForm(d, define, scope)) {
                    Object[] def = parseDefine(d);
                    SSymbol name = (SSymbol) def[0];
                    pendingDefine = new Ast.GlobalSet(globals.cell(name), null, true);
                    return new Analyze(def[1], scope, name.toString());
                }
                return new Analyze(d, scope, null);
            }
            return DONE;
        }

        @Override
        void accept(Expr e) {
            if (pendingDefine != null) {
                body.add(new Ast.GlobalSet(pendingDefine.cell, e, true));
                pendingDefine = null;
            } else {
                body.add(e);
            }
        }

        @Override
        Expr finish() {
            Lambda program = scope.lambda;
            program.body = body.isEmpty() ? new Ast.Const(SConstant.UNSPECIFIED) : body.size() == 1 ? body.get(0) : new Ast.Seq(body);
            return program;
        }
    }
}
