package scheme.compiler;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import scheme.compiler.Ast.Expr;
import scheme.compiler.Ast.Lambda;
import scheme.compiler.Ast.Var;
import scheme.runtime.printer.Printer;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SSymbol;
import scheme.runtime.value.SVector;

/**
 * The core AST as text, for tests ({@link Expander#show}): each local variable is renamed to
 * {@code name.N} in order of first appearance, globals keep their names, and a literal that isn't
 * self-evaluating is quoted. No Java recursion (AGENTS.md rule 4): pieces go on an explicit stack.
 */
final class AstPrinter {

    private final StringBuilder out = new StringBuilder();
    private final Deque<Object> work = new ArrayDeque<>();
    private final Map<Var, String> names = new HashMap<>();

    private AstPrinter() {
    }

    static String print(Expr e) {
        AstPrinter p = new AstPrinter();
        p.work.push(e);
        while (!p.work.isEmpty()) {
            Object item = p.work.pop();
            if (item instanceof String s) {
                p.out.append(s);
            } else if (item instanceof Var v) {
                p.out.append(p.name(v));
            } else {
                p.expand((Expr) item);
            }
        }
        return p.out.toString();
    }

    private String name(Var v) {
        return names.computeIfAbsent(v, x -> x.name + "." + (names.size() + 1));
    }

    /** Pushes the pieces in order: the first one is popped first. */
    private void emit(Object... pieces) {
        for (int i = pieces.length - 1; i >= 0; i--) {
            work.push(pieces[i]);
        }
    }

    /** "(" head, then each item preceded by a space, then ")". */
    private void form(String head, List<?> items) {
        Object[] pieces = new Object[items.size() * 2 + 2];
        pieces[0] = "(" + head;
        for (int i = 0; i < items.size(); i++) {
            pieces[2 * i + 1] = head.isEmpty() && i == 0 ? "" : " ";
            pieces[2 * i + 2] = items.get(i);
        }
        pieces[pieces.length - 1] = ")";
        emit(pieces);
    }

    private void expand(Expr e) {
        if (e instanceof Ast.Const c) {
            Object v = c.value;
            boolean quoted = v instanceof SSymbol || v instanceof SPair || v instanceof SVector || v == scheme.runtime.value.SConstant.EMPTY_LIST;
            String text = Printer.print(v, Printer.Mode.WRITE);
            out.append(quoted ? "'" + text : text);
        } else if (e instanceof Ast.LocalRef r) {
            out.append(name(r.var));
        } else if (e instanceof Ast.LocalSet s) {
            emit("(set! ", s.var, " ", s.value, ")");
        } else if (e instanceof Ast.GlobalRef g) {
            out.append(g.cell.name);
        } else if (e instanceof Ast.GlobalSet g) {
            emit(g.define ? "(define " : "(set! ", g.cell.name + " ", g.value, ")");
        } else if (e instanceof Ast.If i) {
            emit("(if ", i.test, " ", i.consequent, " ", i.alternative, ")");
        } else if (e instanceof Ast.Seq s) {
            form("begin", s.body);
        } else if (e instanceof Ast.Call c) {
            Object[] pieces = new Object[c.arguments.size() * 2 + 3];
            pieces[0] = "(";
            pieces[1] = c.procedure;
            for (int i = 0; i < c.arguments.size(); i++) {
                pieces[2 * i + 2] = " ";
                pieces[2 * i + 3] = c.arguments.get(i);
            }
            pieces[pieces.length - 1] = ")";
            emit(pieces);
        } else if (e instanceof Ast.Letrec l) {
            Object[] pieces = new Object[l.vars.size() * 5 + 4];
            int k = 0;
            pieces[k++] = "(letrec* (";
            for (int i = 0; i < l.vars.size(); i++) {
                pieces[k++] = i == 0 ? "(" : " (";
                pieces[k++] = l.vars.get(i);
                pieces[k++] = " ";
                pieces[k++] = l.inits.get(i);
                pieces[k++] = ")";
            }
            pieces[k++] = ") ";
            pieces[k++] = l.body;
            pieces[k] = ")";
            emit(pieces);
        } else if (e instanceof Lambda fn) {
            Object[] pieces = new Object[fn.params.size() * 2 + 8];
            int k = 0;
            pieces[k++] = "(lambda ";
            if (fn.params.isEmpty() && fn.rest != null) {
                pieces[k++] = fn.rest;
            } else {
                pieces[k++] = "(";
                for (int i = 0; i < fn.params.size(); i++) {
                    pieces[k++] = i == 0 ? "" : " ";
                    pieces[k++] = fn.params.get(i);
                }
                if (fn.rest != null) {
                    pieces[k++] = " . ";
                    pieces[k++] = fn.rest;
                }
                pieces[k++] = ")";
            }
            pieces[k++] = " ";
            pieces[k++] = fn.body;
            pieces[k] = ")";
            Object[] used = new Object[k + 1];
            System.arraycopy(pieces, 0, used, 0, k + 1);
            emit(used);
        } else {
            throw new IllegalStateException("unknown AST node " + e);
        }
    }
}
