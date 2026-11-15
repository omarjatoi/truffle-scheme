package scheme.compiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.reader.ScopeSet;
import scheme.runtime.value.SSymbol;

/**
 * Bindings (Flatt 2016): for each symbol, the scope sets it is bound with and what each binding is.
 * One instance per context holds the top level, which persists from one program to the next (the
 * prelude's macros, a REPL's definitions). Libraries replace it in M3-04.
 *
 * <p>A binding is a {@link CoreForm}, a lexical {@link Ast.Var}, a {@link Macro}, or
 * {@link #GLOBAL}. Core forms aren't entries: an identifier bound to nothing is a core form if its
 * name is one, else a global variable.
 */
public final class SyntacticEnvironment {

    /** A top-level variable, whose cell is the identifier's name. */
    static final Object GLOBAL = new Object() {
        @Override
        public String toString() {
            return "global";
        }
    };

    record Macro(Transformer transformer) {
    }

    record Entry(ScopeSet scopes, Object binding) {
    }

    private final Map<SSymbol, List<Entry>> entries = new HashMap<>();

    /** Defines a top-level macro with a Java transformer (for tests). */
    @TruffleBoundary
    public void defineSyntax(SSymbol name, Transformer transformer) {
        bind(name, ScopeSet.EMPTY, new Macro(transformer));
    }

    /** Binds the symbol with exactly these scopes, replacing a binding with the same scopes. */
    @TruffleBoundary
    void bind(SSymbol name, ScopeSet scopes, Object binding) {
        List<Entry> list = entries.get(name);
        if (list == null) {
            list = new ArrayList<>(1);
            entries.put(name, list);
        }
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).scopes().equals(scopes)) {
                list.set(i, new Entry(scopes, binding));
                return;
            }
        }
        list.add(new Entry(scopes, binding));
    }

    /** Every binding of the symbol, or an empty list. */
    List<Entry> candidates(SSymbol name) {
        List<Entry> list = entries.get(name);
        return list == null ? List.of() : list;
    }
}
