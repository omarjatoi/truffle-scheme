package scheme.compiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.oracle.truffle.api.source.SourceSection;

import scheme.runtime.GlobalCell;
import scheme.runtime.value.SSymbol;

/** The core AST (design.md D3) that the expander produces and the code generator consumes. */
final class Ast {

    private Ast() {
    }

    /**
     * A lexical variable. Boxed (design.md D2) if assigned, or if a letrec* variable a closure
     * captures, unless it is fixed ({@link Closures}).
     */
    static final class Var {
        final SSymbol name;
        /** The lambda whose root holds the variable in a local: set by the expander, then by {@link Closures}. */
        Lambda owner;
        final boolean letrec;
        boolean assigned;
        boolean captured;
        /**
         * A letrec* variable bound to a lambda that no closure can see before it is initialized
         * ("Fixing Letrec", {@link Closures}): never boxed. The closures of its group that capture
         * it before it is initialized get its value patched in.
         */
        boolean fixed;
        /** For a fixed variable: the lambda it is bound to, in whose root it is the running closure. */
        Lambda self;
        /** Bound by an identifier a macro introduced: no code the user wrote can refer to it. */
        boolean introduced;
        /**
         * A parameter of an expression evaluated in a debugger's frame whose variable there is boxed
         * (plan.md M6-05): its argument is that Box itself, so {@code set!} writes the frame's variable.
         */
        boolean preboxed;

        Var(SSymbol name, Lambda owner, boolean letrec) {
            this.name = name;
            this.owner = owner;
            this.letrec = letrec;
        }

        boolean boxed() {
            return preboxed || assigned || (letrec && captured && !fixed);
        }

        /**
         * Whether tools show the variable (plan.md M6-05): not one a macro introduced, nor one of the
         * runtime's internals ({@code %}-prefixed, like a body's anonymous bindings, design.md D14).
         */
        boolean visible() {
            return !introduced && !name.toString().startsWith("%");
        }
    }

    abstract static class Expr {
        /** Where the form this came from is in the program text (design.md D13), or null. */
        SourceSection source;
    }

    static final class Const extends Expr {
        final Object value;

        Const(Object value) {
            this.value = value;
        }
    }

    static final class LocalRef extends Expr {
        final Var var;

        LocalRef(Var var) {
            this.var = var;
        }
    }

    static final class LocalSet extends Expr {
        final Var var;
        final Expr value;

        LocalSet(Var var, Expr value) {
            this.var = var;
            this.value = value;
        }
    }

    static final class GlobalRef extends Expr {
        final GlobalCell cell;

        GlobalRef(GlobalCell cell) {
            this.cell = cell;
        }
    }

    static final class GlobalSet extends Expr {
        final GlobalCell cell;
        final Expr value;
        final boolean define;

        GlobalSet(GlobalCell cell, Expr value, boolean define) {
            this.cell = cell;
            this.value = value;
            this.define = define;
        }
    }

    static final class If extends Expr {
        final Expr test;
        final Expr consequent;
        final Expr alternative;

        If(Expr test, Expr consequent, Expr alternative) {
            this.test = test;
            this.consequent = consequent;
            this.alternative = alternative;
        }
    }

    static final class Seq extends Expr {
        final List<Expr> body;

        Seq(List<Expr> body) {
            this.body = body;
        }
    }

    static final class Call extends Expr {
        /** Not final: the escape analysis turns call/cc into %call/ec (M5-04, {@link EscapeAnalysis}). */
        Expr procedure;
        final List<Expr> arguments;

        Call(Expr procedure, List<Expr> arguments) {
            this.procedure = procedure;
            this.arguments = arguments;
        }
    }

    /**
     * {@code ((lambda (var ...) body) init ...)} with as many inits as parameters, which {@code let}
     * and {@code or} expand to: the inits are evaluated, then bound to locals of the current root, and
     * the body runs in the same root, with no closure (M5-05).
     */
    static final class Let extends Expr {
        final List<Var> vars;
        final List<Expr> inits;
        final Expr body;

        Let(List<Var> vars, List<Expr> inits, Expr body) {
            this.vars = vars;
            this.inits = inits;
            this.body = body;
        }
    }

    static final class Letrec extends Expr {
        final List<Var> vars;
        final List<Expr> inits;
        final Expr body;

        Letrec(List<Var> vars, List<Expr> inits, Expr body) {
            this.vars = vars;
            this.inits = inits;
            this.body = body;
        }
    }

    /** A lambda, or the program itself (no parameters, never a closure). */
    static final class Lambda extends Expr {
        /** The lambda whose root makes this closure: set by the expander, then by {@link Closures}. */
        Lambda parent;
        final boolean program;
        final List<Var> params = new ArrayList<>();
        Var rest;
        /** Every variable whose storage is a local of this lambda's root. */
        final List<Var> owned = new ArrayList<>();
        /** Free variables in capture order: index in the closure's captured array. */
        final Map<Var, Integer> free = new LinkedHashMap<>();
        Expr body;
        String name;
        /** The fixed letrec* variable bound to this lambda, if any ({@link Closures}). */
        Var self;

        Lambda(Lambda parent, boolean program) {
            this.parent = parent;
            this.program = program;
        }

        Var bind(SSymbol varName, boolean letrec) {
            Var v = new Var(varName, this, letrec);
            owned.add(v);
            return v;
        }

        int capture(Var v) {
            Integer index = free.get(v);
            if (index == null) {
                index = free.size();
                free.put(v, index);
            }
            return index;
        }
    }
}
