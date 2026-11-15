package scheme.compiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.source.Source;

import scheme.compiler.Ast.Expr;
import scheme.compiler.Ast.Lambda;
import scheme.compiler.Ast.Var;
import scheme.compiler.SyntacticEnvironment.Entry;
import scheme.compiler.SyntacticEnvironment.Macro;
import scheme.reader.Reader;
import scheme.reader.Scope;
import scheme.reader.ScopeSet;
import scheme.reader.Syntax;
import scheme.runtime.GlobalCell;
import scheme.runtime.GlobalEnvironment;
import scheme.runtime.SchemeError;
import scheme.runtime.SchemeLanguage;
import scheme.runtime.value.SString;
import scheme.runtime.printer.Printer;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SSymbol;
import scheme.runtime.value.SymbolTable;

/**
 * The sets-of-scopes expander (Flatt 2016, design.md D3). It expands syntax objects (from
 * {@link Reader#readSyntax()}) into the core AST: it expands macro uses, resolves every identifier
 * to its binding, turns bodies with internal definitions into {@code letrec*}, and records what
 * closure and assignment conversion need (D2): each lambda's free variables, and which variables
 * are assigned or captured.
 *
 * <p>Hygiene: {@code lambda}, {@code letrec*}, {@code let-syntax}, {@code letrec-syntax} and each
 * body add a fresh scope to the forms they bind in. A macro use flips a fresh introduction scope on
 * the use before calling its transformer and on the result after, so only what the transformer made
 * up keeps it. A macro use in a body also gets a use-site scope, which definitions in that body
 * drop from the identifiers they bind. An identifier refers to the binding of its symbol whose
 * scopes are the largest subset of its own; with none, it is a core form if its name is one, else a
 * global variable.
 *
 * <p>No Java recursion proportional to nesting depth (AGENTS.md rule 4): a work loop drives frames,
 * one per form being expanded, and macro uses expand in a loop.
 */
public final class Expander {

    private final SymbolTable symbols;
    /** The namespace that top-level definitions of this unit (a program or library body) go in. */
    private final GlobalEnvironment namespace;
    /** This unit's top-level scope, on every form of it; null for {@link #show}. */
    private final Scope topScope;
    /** null for {@link #show}, which has no libraries. */
    private final Libraries libraries;
    /** Where {@code include} finds files; null for {@link #show}. */
    private final SourceDir dir;
    /** Persistent top-level bindings. */
    private final SyntacticEnvironment top;
    /** Lexical bindings of this expansion: each one's scopes include a fresh binding scope. */
    private final SyntacticEnvironment local = new SyntacticEnvironment();
    private final Map<SSymbol, CoreForm> core = new HashMap<>();
    private final SSymbol callWithPrompt;
    private final SSymbol ellipsis;
    private final SSymbol underscore;
    /** The innermost syntax object with a source position seen so far, for errors. */
    private Syntax current;

    Expander(SymbolTable symbols, GlobalEnvironment namespace, SyntacticEnvironment top, Scope topScope, Libraries libraries, SourceDir dir) {
        this.symbols = symbols;
        this.namespace = namespace;
        this.top = top;
        this.topScope = topScope;
        this.libraries = libraries;
        this.dir = dir;
        for (CoreForm form : CoreForm.values()) {
            core.put(symbols.intern(form.keyword), form);
        }
        this.callWithPrompt = symbols.intern("%call-with-prompt");
        this.ellipsis = symbols.intern("...");
        this.underscore = symbols.intern("_");
    }

    /**
     * For tests: the core AST of a program as text, each local variable renamed to
     * {@code name.N} in order of first appearance.
     */
    @TruffleBoundary
    public static String show(SymbolTable symbols, GlobalEnvironment globals, SyntacticEnvironment top, String program) {
        Reader reader = Reader.forSource(Source.newBuilder(SchemeLanguage.ID, program, "show.scm").build(), symbols, true);
        List<Object> forms = new ArrayList<>();
        for (Object datum = reader.readSyntax(); datum != SConstant.EOF; datum = reader.readSyntax()) {
            forms.add(datum);
        }
        return AstPrinter.print(new Expander(symbols, globals, top, null, null, null).expandProgram(forms).body);
    }

    // ------------------------------------------------------------------------------------------
    // Identifiers and bindings

    static boolean isIdentifier(Object o) {
        return o instanceof SSymbol || o instanceof Syntax s && s.datum instanceof SSymbol;
    }

    static SSymbol symbol(Object id) {
        return (SSymbol) (id instanceof Syntax s ? s.datum : id);
    }

    static ScopeSet scopes(Object id) {
        return id instanceof Syntax s ? s.scopes : ScopeSet.EMPTY;
    }

    /** What the identifier refers to: a {@link CoreForm}, {@link Var}, {@link Macro} or {@link GlobalCell}. */
    @TruffleBoundary
    private Object resolve(Object id) {
        SSymbol name = symbol(id);
        ScopeSet scopes = scopes(id);
        Entry best = null;
        boolean ambiguous = false;
        for (SyntacticEnvironment env : new SyntacticEnvironment[]{local, top}) {
            for (Entry entry : env.candidates(name)) {
                if (!entry.scopes().isSubsetOf(scopes)) {
                    continue;
                }
                if (best == null || entry.scopes().size() > best.scopes().size()) {
                    best = entry;
                    ambiguous = false;
                } else if (entry.scopes().size() == best.scopes().size()) {
                    ambiguous = true;
                }
            }
        }
        if (ambiguous) {
            throw syntax("ambiguous identifier", id);
        }
        if (best != null) {
            return best.binding();
        }
        CoreForm form = core.get(name);
        return form != null ? form : namespaceOf(id).cell(name);
    }

    /** The namespace an unbound identifier's variable lives in: that of its top-level scope. */
    private GlobalEnvironment namespaceOf(Object id) {
        ScopeSet scopes = scopes(id);
        if (topScope != null && scopes.contains(topScope)) {
            return namespace;
        }
        GlobalEnvironment other = top.namespaceOf(scopes);
        return other != null ? other : namespace;
    }

    /** What an identifier written at this unit's top level refers to (a library's exports). */
    Object resolveTopLevel(Object id) {
        return resolve(topScope == null ? id : Syntax.addScope(id, topScope));
    }

    /** {@code free-identifier=?}: whether two identifiers refer to the same binding, for transformers. */
    @TruffleBoundary
    public boolean freeIdentifierEquals(Object a, Object b) {
        Object x = resolve(a);
        Object y = resolve(b);
        return x == y;
    }

    /** The binding of the form's head identifier, or null if it isn't a list headed by one. */
    private Object headBinding(Object form) {
        return Syntax.e(form) instanceof SPair p && isIdentifier(p.car) ? resolve(p.car) : null;
    }

    /** Expands one macro use (Flatt 2016): flip a fresh introduction scope on the use and on the result. */
    @TruffleBoundary
    private Object expandMacro(Macro macro, Object form, Scope useSite) {
        Scope intro = new Scope();
        ScopeSet adds = useSite == null ? ScopeSet.EMPTY : ScopeSet.EMPTY.add(useSite);
        Object input = Syntax.adjust(form, adds, ScopeSet.EMPTY.add(intro));
        Object result = Syntax.flipScope(macro.transformer().transform(input, this), intro);
        if (result instanceof Syntax s && s.source == null && form instanceof Syntax f && f.source != null) {
            result = s.withSource(f.source);
        }
        enter(result);
        return result;
    }

    /** A {@code define-syntax}, {@code let-syntax} or {@code letrec-syntax} right-hand side, as a macro. */
    private Macro transformer(Object spec) {
        if (isIdentifier(spec) && resolve(spec) instanceof Macro m) {
            if (m.transformer() == null) {
                throw syntax("letrec-syntax keyword used before its transformer is defined", spec);
            }
            return m;
        }
        if (headBinding(spec) == CoreForm.SYNTAX_RULES) {
            return new Macro(new SyntaxRules(spec, this, ellipsis, underscore));
        }
        throw syntax("not a transformer", spec);
    }

    // ------------------------------------------------------------------------------------------
    // Errors and forms

    private void enter(Object form) {
        if (form instanceof Syntax s && s.source != null) {
            current = s;
        }
    }

    /** A syntax error about {@code form}, naming its line (or the innermost line seen). */
    SchemeError syntaxError(String message, Object form) {
        return syntax(message, form);
    }

    @TruffleBoundary
    private SchemeError syntax(String message, Object form) {
        Syntax at = form instanceof Syntax s && s.source != null ? s : current;
        String line = at == null ? "" : " (line " + at.source.getStartLine() + ")";
        return new SchemeError("syntax error: " + message + ": " + Printer.print(Syntax.toDatum(form), Printer.Mode.WRITE) + line);
    }

    /** The elements of a proper list (syntax objects stay wrapped), or a syntax error. */
    @TruffleBoundary
    List<Object> list(Object form) {
        List<Object> items = new ArrayList<>();
        Object rest = Syntax.e(form);
        while (rest instanceof SPair p) {
            items.add(p.car);
            rest = Syntax.e(p.cdr);
        }
        if (rest != SConstant.EMPTY_LIST) {
            throw syntax("not a proper list", form);
        }
        return items;
    }

    private static List<Object> addScope(List<Object> forms, Scope scope) {
        List<Object> result = new ArrayList<>(forms.size());
        for (Object form : forms) {
            result.add(Syntax.addScope(form, scope));
        }
        return result;
    }

    /** Binding identifiers already bound in one binding form, to reject duplicates. */
    private final class Binder {
        final List<Object> bound = new ArrayList<>();

        /** Checks that the identifier is one, and isn't bound here already. */
        SSymbol check(Object id, Object form, String what) {
            if (!isIdentifier(id)) {
                throw syntax("invalid " + what + " " + Printer.print(Syntax.toDatum(id), Printer.Mode.WRITE), form);
            }
            for (Object other : bound) {
                if (symbol(other) == symbol(id) && scopes(other).equals(scopes(id))) {
                    throw syntax("duplicate " + what + " " + symbol(id), form);
                }
            }
            return symbol(id);
        }

        /** Binds the identifier lexically, with exactly its scopes. */
        void bind(Object id, Object binding, Object form, String what) {
            check(id, form, what);
            bound.add(id);
            local.bind(symbol(id), scopes(id), binding);
        }
    }

    private Expr reference(Var v, Lambda lambda, Object id) {
        if (v.owner != lambda) {
            v.captured = true;
            for (Lambda l = lambda; l != v.owner; l = l.parent) {
                if (l == null) {
                    throw syntax("variable used outside its scope", id);
                }
                l.capture(v);
            }
        }
        return new Ast.LocalRef(v);
    }

    /** A lambda's formals and body, from {@code lambda} or {@code (define (name . formals) body ...)}. */
    private record LambdaSpec(Object formals, List<Object> body, Object form) {
    }

    /** A definition: the identifier it binds and its right-hand side (a form or a {@link LambdaSpec}). */
    private record Definition(Object id, Object value) {
    }

    /** (define id expr) or (define (id . formals) body ...). */
    private Definition parseDefine(Object form) {
        List<Object> f = list(form);
        if (f.size() >= 2 && isIdentifier(f.get(1))) {
            if (f.size() != 3) {
                throw syntax("define takes a variable and an expression", form);
            }
            return new Definition(f.get(1), f.get(2));
        }
        if (f.size() >= 3 && Syntax.e(f.get(1)) instanceof SPair p && isIdentifier(p.car)) {
            return new Definition(p.car, new LambdaSpec(p.cdr, f.subList(2, f.size()), form));
        }
        throw syntax("invalid define", form);
    }

    /** (define-syntax keyword spec): {keyword, spec}. */
    private Object[] parseDefineSyntax(Object form) {
        List<Object> f = list(form);
        if (f.size() != 3 || !isIdentifier(f.get(1))) {
            throw syntax("define-syntax takes a keyword and a transformer", form);
        }
        return new Object[]{f.get(1), f.get(2)};
    }

    // ------------------------------------------------------------------------------------------
    // The work loop

    private static final Object DONE = new Object();

    /** A form to expand in a lambda. {@code name} names a lambda (from its definition). */
    private record Expand(Object form, Lambda lambda, String name) {
    }

    private abstract static class Frame {
        /** An {@link Expand}, a {@link Frame} to run, or {@link #DONE}. */
        abstract Object next();

        abstract void accept(Expr e);

        abstract Expr finish();
    }

    private Expr run(Frame root) {
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(root);
        Expr delivered = null;
        while (true) {
            Frame frame = stack.peek();
            if (delivered != null) {
                frame.accept(delivered);
                delivered = null;
            }
            Object step = frame.next();
            if (step == DONE) {
                stack.pop();
                Expr e = frame.finish();
                if (stack.isEmpty()) {
                    return e;
                }
                delivered = e;
            } else if (step instanceof Frame f) {
                stack.push(f);
            } else {
                Object result = expand((Expand) step);
                if (result instanceof Frame f) {
                    stack.push(f);
                } else {
                    delivered = (Expr) result;
                }
            }
        }
    }

    /** Expands a program or library body, adding this unit's top-level scope to its forms. */
    Lambda expandProgram(List<Object> forms) {
        Lambda program = new Lambda(null, true);
        return (Lambda) run(new ProgramFrame(topScope == null ? forms : addScope(forms, topScope), program));
    }

    // ------------------------------------------------------------------------------------------
    // begin, include, cond-expand and import at the top level and in bodies

    /** The forms that a begin, include or cond-expand splices in place of itself, or null for other forms. */
    private List<Object> splice(Object binding, Object form) {
        if (binding == CoreForm.BEGIN) {
            List<Object> f = list(form);
            return f.subList(1, f.size());
        } else if (binding == CoreForm.INCLUDE || binding == CoreForm.INCLUDE_CI) {
            return include(form, binding == CoreForm.INCLUDE_CI);
        } else if (binding == CoreForm.COND_EXPAND) {
            return condExpand(form);
        }
        return null;
    }

    /** (import set ...): binds each import set's names under this unit's top-level scope. */
    private void importAll(Object form) {
        if (libraries == null) {
            return;
        }
        if (topScope == null) {
            throw syntax("import needs a top level", form);
        }
        List<Object> f = list(form);
        for (Object set : f.subList(1, f.size())) {
            for (Map.Entry<SSymbol, Object> e : libraries.importSet(set, this).entrySet()) {
                top.bind(e.getKey(), ScopeSet.EMPTY.add(topScope), e.getValue());
            }
        }
    }

    /**
     * (include file ...) and include-ci: every form of each file, in order, with the scopes of the
     * include form, so they mean what they would have meant written in its place.
     */
    List<Object> include(Object form, boolean foldCase) {
        List<Object> f = list(form);
        List<Object> forms = new ArrayList<>();
        ScopeSet scopes = scopes(form);
        for (Object name : f.subList(1, f.size())) {
            if (!(Syntax.e(name) instanceof SString file)) {
                throw syntax("include takes file names", form);
            }
            for (Object read : readFile(file.toString(), foldCase, form)) {
                forms.add(Syntax.adjust(read, scopes, ScopeSet.EMPTY));
            }
        }
        return forms;
    }

    /** Every form in a file found in {@link #dir}, as syntax. */
    List<Object> readFile(String name, boolean foldCase, Object form) {
        Source source = dir == null ? null : dir.find(name);
        if (source == null) {
            throw syntax("cannot find file " + name, form);
        }
        Reader reader = Reader.forSource(source, symbols, true);
        reader.setFoldCase(foldCase);
        List<Object> forms = new ArrayList<>();
        for (Object datum = reader.readSyntax(); datum != SConstant.EOF; datum = reader.readSyntax()) {
            forms.add(datum);
        }
        return forms;
    }

    /** (cond-expand (requirement form ...) ... [(else form ...)]): the forms of the first clause that holds. */
    List<Object> condExpand(Object form) {
        List<Object> f = list(form);
        for (Object clause : f.subList(1, f.size())) {
            List<Object> c = list(clause);
            if (c.isEmpty()) {
                throw syntax("invalid cond-expand clause", clause);
            }
            Object requirement = c.get(0);
            if (isIdentifier(requirement) && symbol(requirement).toString().equals("else") || holds(requirement, form)) {
                return c.subList(1, c.size());
            }
        }
        return List.of();
    }

    /**
     * Whether a feature requirement holds: a feature identifier, {@code (library name)},
     * {@code (and ...)}, {@code (or ...)} or {@code (not req)}. Evaluated on an explicit stack
     * (rule 4): each frame is an and, or or not with the results of its operands so far.
     */
    private boolean holds(Object requirement, Object form) {
        Deque<Object[]> stack = new ArrayDeque<>();
        Object next = requirement;
        Boolean result = null;
        while (true) {
            if (next != null) {
                Object d = Syntax.toDatum(next);
                next = null;
                if (d instanceof SSymbol feature) {
                    result = Libraries.FEATURES.contains(feature.toString());
                } else if (d instanceof SPair p && p.car instanceof SSymbol op) {
                    List<Object> args = list(d);
                    switch (op.toString()) {
                        case "library":
                            if (args.size() != 2) {
                                throw syntax("invalid feature requirement", form);
                            }
                            result = libraries != null && libraries.exists(args.get(1));
                            break;
                        case "and":
                        case "or":
                        case "not":
                            if (op.toString().equals("not") && args.size() != 2) {
                                throw syntax("invalid feature requirement", form);
                            }
                            stack.push(new Object[]{op.toString(), args, 1});
                            break;
                        default:
                            throw syntax("invalid feature requirement", form);
                    }
                } else {
                    throw syntax("invalid feature requirement", form);
                }
            }
            if (result == null) {
                // A new and/or/not frame: evaluate its first operand, or finish it if it has none.
                Object[] frame = stack.peek();
                @SuppressWarnings("unchecked")
                List<Object> args = (List<Object>) frame[1];
                if (args.size() > 1) {
                    next = args.get(1);
                    frame[2] = 2;
                    continue;
                }
                stack.pop();
                result = frame[0].equals("and");
            }
            while (true) {
                Object[] frame = stack.peek();
                if (frame == null) {
                    return result;
                }
                String op = (String) frame[0];
                @SuppressWarnings("unchecked")
                List<Object> args = (List<Object>) frame[1];
                int index = (Integer) frame[2];
                if (op.equals("not")) {
                    stack.pop();
                    result = !result;
                } else if (op.equals("and") && !result || op.equals("or") && result || index >= args.size()) {
                    stack.pop();
                } else {
                    next = args.get(index);
                    frame[2] = index + 1;
                    result = null;
                    break;
                }
            }
        }
    }

    /** One step: an Expr for simple forms, or a Frame for compound ones. */
    private Object expand(Expand step) {
        Lambda lambda = step.lambda();
        if (step.form() instanceof Expr e) {
            return e;
        }
        if (step.form() instanceof LambdaSpec spec) {
            return new LambdaFrame(spec.formals(), spec.body(), lambda, step.name(), spec.form());
        }
        Object form = step.form();
        while (true) {
            enter(form);
            Object d = Syntax.e(form);
            if (d instanceof SSymbol) {
                Object binding = resolve(form);
                if (binding instanceof Var v) {
                    return reference(v, lambda, form);
                } else if (binding instanceof GlobalCell cell) {
                    return new Ast.GlobalRef(cell);
                }
                throw syntax("syntax keyword used as an expression", form);
            }
            if (!(d instanceof SPair p)) {
                if (d == SConstant.EMPTY_LIST) {
                    throw syntax("() is not an expression", form);
                }
                return new Ast.Const(Syntax.toDatum(form));
            }
            Object binding = isIdentifier(p.car) ? resolve(p.car) : null;
            if (binding instanceof Macro m) {
                form = expandMacro(m, form, null);
            } else if (binding instanceof CoreForm c) {
                return coreForm(c, form, step);
            } else {
                return new SeqLikeFrame(list(form), lambda, SeqLikeFrame.CALL);
            }
        }
    }

    private Object coreForm(CoreForm c, Object form, Expand step) {
        Lambda lambda = step.lambda();
        List<Object> f = list(form);
        switch (c) {
            case QUOTE:
                if (f.size() != 2) {
                    throw syntax("quote takes one datum", form);
                }
                return new Ast.Const(Syntax.toDatum(f.get(1)));
            case IF:
                if (f.size() != 3 && f.size() != 4) {
                    throw syntax("if takes 2 or 3 subforms", form);
                }
                return new SeqLikeFrame(f.subList(1, f.size()), lambda, SeqLikeFrame.IF);
            case SET:
                if (f.size() != 3 || !isIdentifier(f.get(1))) {
                    throw syntax("set! takes a variable and an expression", form);
                }
                Object binding = resolve(f.get(1));
                Expr target;
                if (binding instanceof Var v) {
                    target = reference(v, lambda, f.get(1));
                } else if (binding instanceof GlobalCell cell) {
                    if (!namespaceOf(f.get(1)).owns(cell)) {
                        throw syntax("set! of an imported variable", form);
                    }
                    target = new Ast.GlobalRef(cell);
                } else {
                    throw syntax("set! of a syntax keyword", form);
                }
                return new SetFrame(target, f.get(2), lambda);
            case LAMBDA:
                if (f.size() < 3) {
                    throw syntax("lambda needs formals and a body", form);
                }
                return new LambdaFrame(f.get(1), f.subList(2, f.size()), lambda, step.name(), form);
            case BEGIN:
                if (f.size() < 2) {
                    throw syntax("begin needs at least one expression here", form);
                }
                return new SeqLikeFrame(f.subList(1, f.size()), lambda, SeqLikeFrame.SEQ);
            case LETREC_STAR:
                return letrec(f, form, lambda);
            case LET_SYNTAX:
            case LETREC_SYNTAX:
                return letSyntax(f, form, lambda, c == CoreForm.LETREC_SYNTAX);
            case PROMPT:
                return prompt(f, form, lambda);
            case DEFINE:
            case DEFINE_SYNTAX:
                throw syntax(c.keyword + " is only allowed at the top level or at the start of a body", form);
            case INCLUDE:
            case INCLUDE_CI:
            case COND_EXPAND: {
                List<Object> forms = c == CoreForm.COND_EXPAND ? condExpand(form) : include(form, c == CoreForm.INCLUDE_CI);
                if (forms.isEmpty()) {
                    return new Ast.Const(SConstant.UNSPECIFIED);
                }
                return new SeqLikeFrame(forms, lambda, SeqLikeFrame.SEQ);
            }
            case IMPORT:
            case DEFINE_LIBRARY:
                throw syntax(c.keyword + " is only allowed at the top level", form);
            case SYNTAX_RULES:
                throw syntax("syntax-rules is only allowed as a transformer", form);
            case GUARD:
                throw syntax("%guard is not supported until M3-06", form);
            default:
                throw new IllegalStateException("unhandled core form " + c);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Frames

    /** if, begin (as an expression), and procedure calls: expand every subform in order. */
    private static final class SeqLikeFrame extends Frame {
        static final int IF = 0;
        static final int SEQ = 1;
        static final int CALL = 2;

        /** Forms to expand, frames to run, or finished expressions. */
        final List<Object> forms;
        final Lambda lambda;
        final int kind;
        final List<Expr> done = new ArrayList<>();
        int index;

        SeqLikeFrame(List<Object> forms, Lambda lambda, int kind) {
            this.forms = forms;
            this.lambda = lambda;
            this.kind = kind;
        }

        @Override
        Object next() {
            if (index == forms.size()) {
                return DONE;
            }
            Object form = forms.get(index++);
            return form instanceof Frame ? form : new Expand(form, lambda, null);
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
        final Lambda lambda;
        boolean started;
        Expr result;

        SetFrame(Expr target, Object value, Lambda lambda) {
            this.target = target;
            this.value = value;
            this.lambda = lambda;
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
            return new Expand(value, lambda, null);
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

    /** A lambda: a fresh scope on its formals and body. */
    private final class LambdaFrame extends Frame {
        final Lambda fn;
        final List<Object> body;
        boolean started;

        LambdaFrame(Object formals, List<Object> body, Lambda outer, String name, Object form) {
            this.fn = new Lambda(outer, false);
            this.fn.name = name;
            Scope scope = new Scope();
            this.body = addScope(body, scope);
            Binder binder = new Binder();
            Object rest = Syntax.addScope(formals, scope);
            while (Syntax.e(rest) instanceof SPair p) {
                fn.params.add(parameter(p.car, binder, form));
                rest = p.cdr;
            }
            if (Syntax.e(rest) != SConstant.EMPTY_LIST) {
                fn.rest = parameter(rest, binder, form);
            }
        }

        private Var parameter(Object id, Binder binder, Object form) {
            Var v = fn.bind(binder.check(id, form, "parameter"), false);
            binder.bind(id, v, form, "parameter");
            return v;
        }

        @Override
        Object next() {
            if (started) {
                return DONE;
            }
            started = true;
            return new BodyFrame(body, fn, null);
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

    /**
     * A body (R7RS §5.3.2): definitions, then at least one expression, as {@code letrec*}. Each
     * form's head is expanded first to find the definitions, {@code begin}s splice, and
     * {@code define-syntax} binds a macro for the rest of the body. Then the right-hand sides and
     * expressions are expanded in order.
     */
    private final class BodyFrame extends Frame {
        final Lambda lambda;
        final List<Var> vars = new ArrayList<>();
        final List<Object> inits = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        final List<Object> exprs = new ArrayList<>();
        final List<Expr> initExprs = new ArrayList<>();
        final List<Expr> bodyExprs = new ArrayList<>();
        int index;

        /** {@code where} is the form the body belongs to, for errors (null for a lambda's). */
        BodyFrame(List<Object> body, Lambda lambda, Object where) {
            this.lambda = lambda;
            Scope scope = new Scope();
            Deque<Object> queue = new ArrayDeque<>(addScope(body, scope));
            ScopeSet useSites = ScopeSet.EMPTY;
            Binder binder = new Binder();
            while (!queue.isEmpty()) {
                Object form = queue.pollFirst();
                enter(form);
                if (!exprs.isEmpty()) {
                    exprs.add(form);
                    continue;
                }
                Object binding = headBinding(form);
                while (binding instanceof Macro m) {
                    Scope useSite = new Scope();
                    useSites = useSites.add(useSite);
                    form = expandMacro(m, form, useSite);
                    binding = headBinding(form);
                }
                List<Object> spliced = splice(binding, form);
                if (spliced != null) {
                    for (int i = spliced.size() - 1; i >= 0; i--) {
                        queue.addFirst(spliced.get(i));
                    }
                } else if (binding == CoreForm.DEFINE) {
                    Definition def = parseDefine(form);
                    Object id = withoutUseSites(def.id(), useSites);
                    Var v = lambda.bind(binder.check(id, form, "definition of"), true);
                    binder.bind(id, v, form, "definition of");
                    vars.add(v);
                    inits.add(def.value());
                    names.add(symbol(id).toString());
                } else if (binding == CoreForm.DEFINE_SYNTAX) {
                    Object[] def = parseDefineSyntax(form);
                    binder.bind(withoutUseSites(def[0], useSites), transformer(def[1]), form, "definition of");
                } else {
                    exprs.add(form);
                }
            }
            if (exprs.isEmpty()) {
                throw syntax("a body needs at least one expression", where == null ? SConstant.EMPTY_LIST : where);
            }
        }

        private static Object withoutUseSites(Object id, ScopeSet useSites) {
            return id instanceof Syntax s ? s.withScopes(s.scopes.minus(useSites)) : id;
        }

        @Override
        Object next() {
            if (index < inits.size()) {
                int i = index++;
                return new Expand(inits.get(i), lambda, names.get(i));
            }
            int i = index++ - inits.size();
            return i < exprs.size() ? new Expand(exprs.get(i), lambda, null) : DONE;
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

    /** (letrec* ((var init) ...) body ...): a fresh scope on everything. */
    private Frame letrec(List<Object> f, Object form, Lambda lambda) {
        if (f.size() < 3) {
            throw syntax("letrec* needs bindings and a body", form);
        }
        Scope scope = new Scope();
        Binder binder = new Binder();
        List<Var> vars = new ArrayList<>();
        List<Object> inits = new ArrayList<>();
        for (Object binding : list(Syntax.addScope(f.get(1), scope))) {
            List<Object> b = list(binding);
            if (b.size() != 2) {
                throw syntax("invalid letrec* binding", binding);
            }
            Var v = lambda.bind(binder.check(b.get(0), form, "binding of"), true);
            binder.bind(b.get(0), v, form, "binding of");
            vars.add(v);
            inits.add(b.get(1));
        }
        List<Object> body = addScope(f.subList(2, f.size()), scope);
        return new Frame() {
            final List<Expr> initExprs = new ArrayList<>();
            Expr bodyExpr;
            int index;

            @Override
            Object next() {
                if (index < inits.size()) {
                    int i = index++;
                    return new Expand(inits.get(i), lambda, vars.get(i).name.toString());
                }
                if (index++ == inits.size()) {
                    return new BodyFrame(body, lambda, form);
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

    /**
     * (let-syntax ((keyword spec) ...) body ...) and letrec-syntax: a fresh scope on the keywords and
     * body, and for letrec-syntax on the specs too. The body is a body (R7RS §4.3.1).
     */
    private Frame letSyntax(List<Object> f, Object form, Lambda lambda, boolean recursive) {
        if (f.size() < 3) {
            throw syntax((recursive ? "letrec-syntax" : "let-syntax") + " needs bindings and a body", form);
        }
        Scope scope = new Scope();
        Binder binder = new Binder();
        List<Object[]> specs = new ArrayList<>();
        for (Object binding : list(f.get(1))) {
            List<Object> b = list(binding);
            if (b.size() != 2 || !isIdentifier(b.get(0))) {
                throw syntax("invalid syntax binding", binding);
            }
            specs.add(new Object[]{Syntax.addScope(b.get(0), scope), recursive ? Syntax.addScope(b.get(1), scope) : b.get(1)});
        }
        if (recursive) {
            // Every keyword is bound (to a placeholder) before any spec is evaluated.
            for (Object[] spec : specs) {
                binder.bind(spec[0], new Macro(null), form, "keyword");
            }
            for (Object[] spec : specs) {
                local.bind(symbol(spec[0]), scopes(spec[0]), transformer(spec[1]));
            }
        } else {
            List<Macro> macros = new ArrayList<>();
            for (Object[] spec : specs) {
                macros.add(transformer(spec[1]));
            }
            for (int i = 0; i < specs.size(); i++) {
                binder.bind(specs.get(i)[0], macros.get(i), form, "keyword");
            }
        }
        return new BodyFrame(addScope(f.subList(2, f.size()), scope), lambda, form);
    }

    /** (%prompt tag body ...): {@code (%call-with-prompt tag (lambda () body ...))} (D4). */
    private Frame prompt(List<Object> f, Object form, Lambda lambda) {
        if (f.size() < 3) {
            throw syntax("%prompt needs a tag and a body", form);
        }
        List<Object> parts = new ArrayList<>();
        parts.add(new Ast.GlobalRef((libraries == null ? namespace : libraries.primitives()).cell(callWithPrompt)));
        parts.add(f.get(1));
        parts.add(new LambdaFrame(SConstant.EMPTY_LIST, f.subList(2, f.size()), lambda, "%prompt", form));
        return new SeqLikeFrame(parts, lambda, SeqLikeFrame.CALL);
    }

    /**
     * A program or library body (design.md D6, R7RS §5.1). Like a body, in two passes: first each
     * form's head is expanded in order to find imports, macro definitions and definitions, which
     * take effect at once (so a definition covers the whole program, including references before
     * it); then the right-hand sides and expressions are expanded in order. Definitions go in this
     * unit's namespace and shadow imports of the same name.
     */
    private final class ProgramFrame extends Frame {
        final Lambda program;
        final List<Expr> body = new ArrayList<>();
        /** Each a form to expand, or a {cell, value, name} definition. */
        final List<Object> items = new ArrayList<>();
        int index;
        GlobalCell pendingDefine;

        ProgramFrame(List<Object> forms, Lambda program) {
            this.program = program;
            Deque<Object> queue = new ArrayDeque<>(forms);
            while (!queue.isEmpty()) {
                Object form = queue.pollFirst();
                enter(form);
                Object binding = headBinding(form);
                while (binding instanceof Macro m) {
                    form = expandMacro(m, form, null);
                    binding = headBinding(form);
                }
                List<Object> spliced = splice(binding, form);
                if (spliced != null) {
                    for (int i = spliced.size() - 1; i >= 0; i--) {
                        queue.addFirst(spliced.get(i));
                    }
                } else if (binding == CoreForm.IMPORT) {
                    importAll(form);
                } else if (binding == CoreForm.DEFINE_LIBRARY) {
                    if (libraries == null) {
                        throw syntax("define-library needs libraries", form);
                    }
                    libraries.declare(form, dir, Expander.this);
                } else if (binding == CoreForm.DEFINE) {
                    Definition def = parseDefine(form);
                    SSymbol name = symbol(def.id());
                    GlobalCell cell = namespace.cell(name);
                    if (resolve(def.id()) != cell) {
                        // Shadows an import, a macro or a keyword.
                        top.bind(name, scopes(def.id()), cell);
                    }
                    items.add(new Object[]{cell, def.value(), name.toString()});
                } else if (binding == CoreForm.DEFINE_SYNTAX) {
                    Object[] def = parseDefineSyntax(form);
                    top.bind(symbol(def[0]), scopes(def[0]), transformer(def[1]));
                } else {
                    items.add(form);
                }
            }
        }

        @Override
        Object next() {
            if (index == items.size()) {
                return DONE;
            }
            Object item = items.get(index++);
            if (item instanceof Object[] def) {
                pendingDefine = (GlobalCell) def[0];
                return new Expand(def[1], program, (String) def[2]);
            }
            return new Expand(item, program, null);
        }

        @Override
        void accept(Expr e) {
            if (pendingDefine != null) {
                body.add(new Ast.GlobalSet(pendingDefine, e, true));
                pendingDefine = null;
            } else {
                body.add(e);
            }
        }

        @Override
        Expr finish() {
            program.body = body.isEmpty() ? new Ast.Const(SConstant.UNSPECIFIED) : body.size() == 1 ? body.get(0) : new Ast.Seq(body);
            return program;
        }
    }
}
