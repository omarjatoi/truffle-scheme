package scheme.compiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.reader.Scope;
import scheme.reader.ScopeSet;
import scheme.runtime.GlobalCell;
import scheme.runtime.GlobalEnvironment;
import scheme.runtime.value.SSymbol;

/**
 * Bindings (Flatt 2016): for each symbol, the scope sets it is bound with and what each binding is.
 * One instance per context holds the top-level bindings of every library and of the interaction
 * environment (imports, definitions, macros), each under that unit's own top-level scope; it
 * persists from one program to the next.
 *
 * <p>A binding is a {@link CoreForm}, a lexical {@link Ast.Var}, a {@link Macro}, or a
 * {@link GlobalCell}. Core forms are bindings like any other, exported by (r7rs %primitives)
 * (design.md D15). An identifier bound to nothing is the cell of that name in the namespace of its
 * top-level scope ({@link #namespaceOf}); only in the {@link #interaction} environment is it first a
 * core form if its name is one.
 */
public final class SyntacticEnvironment {

    record Macro(Transformer transformer) {
    }

    record Entry(ScopeSet scopes, Object binding) {
    }

    private final Map<SSymbol, List<Entry>> entries = new HashMap<>();
    /** Each library's and the interaction environment's top-level scope, and its namespace. */
    private final Map<Scope, GlobalEnvironment> namespaces = new HashMap<>();

    /** The interaction environment's namespace, the one where core forms are found by name (D15). */
    private GlobalEnvironment interaction;

    void registerNamespace(Scope scope, GlobalEnvironment namespace) {
        namespaces.put(scope, namespace);
    }

    void registerInteraction(Scope scope, GlobalEnvironment namespace) {
        registerNamespace(scope, namespace);
        interaction = namespace;
    }

    boolean isInteraction(GlobalEnvironment namespace) {
        return namespace == interaction;
    }

    /** The namespace of the first top-level scope among {@code scopes}, or null. */
    GlobalEnvironment namespaceOf(ScopeSet scopes) {
        for (int i = 0; i < scopes.size(); i++) {
            GlobalEnvironment namespace = namespaces.get(scopes.get(i));
            if (namespace != null) {
                return namespace;
            }
        }
        return null;
    }

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

    /** Every symbol's bindings, for {@link PreludeImage}. */
    Map<SSymbol, List<Entry>> entries() {
        return entries;
    }

    /** Every top-level scope and its namespace, for {@link PreludeImage}. */
    Map<Scope, GlobalEnvironment> namespaces() {
        return namespaces;
    }

    /** Adds a binding that {@link PreludeImage} saved, after the symbol's others, without looking for one to replace. */
    void restore(SSymbol name, ScopeSet scopes, Object binding) {
        List<Entry> list = entries.get(name);
        if (list == null) {
            list = new ArrayList<>(1);
            entries.put(name, list);
        }
        list.add(new Entry(scopes, binding));
    }

    /** Every binding of the symbol, or an empty list. */
    List<Entry> candidates(SSymbol name) {
        List<Entry> list = entries.get(name);
        return list == null ? List.of() : list;
    }
}
