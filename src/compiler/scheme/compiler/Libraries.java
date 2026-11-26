package scheme.compiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.source.Source;

import scheme.reader.Scope;
import scheme.reader.Syntax;
import scheme.runtime.Features;
import scheme.runtime.GlobalCell;
import scheme.runtime.GlobalEnvironment;
import scheme.runtime.SchemeLogs;
import scheme.runtime.printer.Printer;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SSymbol;
import scheme.runtime.value.SymbolTable;

/**
 * The libraries of one context (R7RS §5.6, design.md D7). Each library is instantiated once, on
 * first import: its declarations are processed in order, its body is expanded in its own
 * namespace under its own top-level scope and run, and its exports map names to bindings (cells,
 * macros or core forms).
 *
 * <p>A library comes from, in order: a {@code define-library} earlier in a program, the prelude's
 * resources ({@code (scheme base)} is {@code scheme.base.sld}), or a directory on the library path
 * ({@code (chibi test)} is {@code chibi/test.sld}). {@code (r7rs %primitives)} is built in: the
 * Java primitives and the core forms.
 */
final class Libraries {

    /** The feature identifiers cond-expand recognizes (R7RS Appendix B): the ones features returns. */
    static final Set<String> FEATURES = Set.copyOf(Features.NAMES);

    /** The library of the primitives and the core forms. */
    static final String PRIMITIVES = "(r7rs %primitives)";

    /** Runs an expanded library body (with its prompt, design.md D6), compiled in {@code topLevel}. */
    interface Runner {
        void run(Ast.Lambda body, CompilerState.TopLevel topLevel);
    }

    /** An instantiated library. */
    record Library(String name, Map<SSymbol, Object> exports) {
    }

    private record Declared(Object form, SourceDir dir) {
    }

    private final SymbolTable symbols;
    private final SyntacticEnvironment top;
    private final GlobalEnvironment primitives;
    private final Runner runner;
    private TruffleLanguage.Env env;
    private final List<String> path = new ArrayList<>();
    private final Map<String, Library> loaded = new HashMap<>();
    private final Map<String, Declared> declared = new HashMap<>();
    private final Set<String> loading = new HashSet<>();
    /** The prelude's files, as this context reads them (for {@link PreludeImage}). */
    private final SourceDir.Resources resources = new SourceDir.Resources(new LinkedHashMap<>());

    /** {@code path}: the library path option, directories separated by the path separator. */
    Libraries(SymbolTable symbols, SyntacticEnvironment top, GlobalEnvironment primitives, Runner runner, TruffleLanguage.Env env, String path) {
        this.symbols = symbols;
        this.top = top;
        this.primitives = primitives;
        this.runner = runner;
        patch(env, path);
        Map<SSymbol, Object> exports = new LinkedHashMap<>();
        for (GlobalCell cell : primitives.cells()) {
            exports.put(cell.name, cell);
        }
        // Core forms are bindings like any other (design.md D15): the standard libraries re-export them.
        for (CoreForm form : CoreForm.values()) {
            exports.put(symbols.intern(form.keyword), form);
        }
        loaded.put(PRIMITIVES, new Library(PRIMITIVES, exports));
    }

    /**
     * Where libraries are found from now on: the Env's files and {@code path}, the library path
     * option. A pre-initialized context gets its run's here (design.md D17).
     */
    void patch(TruffleLanguage.Env newEnv, String newPath) {
        this.env = newEnv;
        this.path.clear();
        for (String dir : newPath.split(java.util.regex.Pattern.quote(newEnv.getPathSeparator()))) {
            if (!dir.isEmpty()) {
                this.path.add(dir);
            }
        }
    }

    /** The prelude's files, as this context reads them: every lookup, for {@link PreludeImage}. */
    SourceDir.Resources resources() {
        return resources;
    }

    /** Every library instantiated so far, by name, for {@link PreludeImage}. */
    Map<String, Library> loaded() {
        return loaded;
    }

    /** Adds a library that {@link PreludeImage} saved, as if it had been instantiated. */
    void restore(Library library) {
        loaded.put(library.name(), library);
    }

    /** The primitives' namespace, (r7rs %primitives). */
    GlobalEnvironment primitives() {
        return primitives;
    }

    private static String key(Object name) {
        return Printer.print(Syntax.toDatum(name), Printer.Mode.WRITE);
    }

    /** The name's parts as strings, or null if it isn't a library name (identifiers and exact integers). */
    private static List<String> parts(Object name) {
        List<String> parts = new ArrayList<>();
        Object rest = Syntax.toDatum(name);
        if (!(rest instanceof SPair)) {
            return null;
        }
        while (rest instanceof SPair p) {
            if (!(p.car instanceof SSymbol) && !(p.car instanceof Long n && n >= 0)) {
                return null;
            }
            parts.add(p.car.toString());
            rest = p.cdr;
        }
        return rest == SConstant.EMPTY_LIST ? parts : null;
    }

    /** {@code (define-library name declaration ...)} in a program: instantiated on first import. */
    @TruffleBoundary
    void declare(Object form, SourceDir dir, Expander errors) {
        Object datum = Syntax.toDatum(form);
        if (!(datum instanceof SPair p) || !(p.cdr instanceof SPair q) || parts(q.car) == null) {
            throw errors.syntaxError("invalid define-library", form);
        }
        // Stripped of the program's scopes: a library sees only what it imports. Its code keeps its
        // positions in the program, as a library file's has (design.md D8).
        declared.put(key(q.car), new Declared(Syntax.withoutScopes(form), dir));
    }

    /** Whether the library exists, for cond-expand's {@code (library name)}; doesn't load it. */
    @TruffleBoundary
    boolean exists(Object name) {
        List<String> parts = parts(name);
        if (parts == null) {
            return false;
        }
        String key = key(name);
        return loaded.containsKey(key) || declared.containsKey(key) || locate(parts) != null;
    }

    /** The library's file: a prelude resource, else the first on the library path; or null. */
    private Object[] locate(List<String> parts) {
        String resource = String.join(".", parts).replace("%", "") + ".sld";
        Source source = resources.find(resource);
        if (source != null) {
            return new Object[]{source, resources};
        }
        String relative = String.join("/", parts) + ".sld";
        for (String dir : path) {
            SourceDir files = new SourceDir.Files(env, env.getPublicTruffleFile(dir));
            source = files.find(relative);
            if (source != null) {
                return new Object[]{source, files.dirOf(source)};
            }
        }
        return null;
    }

    /** The file at {@code relative} in the first directory of the library path that has it, or null. */
    Source findOnPath(String relative) {
        for (String dir : path) {
            Source source = new SourceDir.Files(env, env.getPublicTruffleFile(dir)).find(relative);
            if (source != null) {
                return source;
            }
        }
        return null;
    }

    /** The library, instantiated if this is its first import. */
    @TruffleBoundary
    Library instantiate(Object name, Expander errors) {
        List<String> parts = parts(name);
        if (parts == null) {
            throw errors.syntaxError("invalid library name", name);
        }
        String key = key(name);
        Library library = loaded.get(key);
        if (library != null) {
            return library;
        }
        if (loading.contains(key)) {
            throw errors.syntaxError("library imports itself", name);
        }
        long start = System.nanoTime();
        String from = "a define-library evaluated before";
        Declared declaration = declared.get(key);
        if (declaration == null) {
            Object[] found = locate(parts);
            if (found == null) {
                if (SchemeLogs.on(SchemeLogs.LIBRARY)) {
                    SchemeLogs.LIBRARY.fine("library " + key + " not found: no " + String.join("/", parts) + ".sld in the library path " + path);
                }
                throw errors.syntaxError("library not found", name);
            }
            from = ((Source) found[0]).getPath() != null ? ((Source) found[0]).getPath() : ((Source) found[0]).getName();
            declaration = new Declared(findDefinition((Source) found[0], key, errors, name), (SourceDir) found[1]);
        }
        loading.add(key);
        try {
            library = define(key, declaration, errors);
        } finally {
            loading.remove(key);
        }
        loaded.put(key, library);
        if (SchemeLogs.on(SchemeLogs.LIBRARY)) {
            SchemeLogs.LIBRARY.fine("library " + key + " loaded from " + from + " in " + SchemeLogs.millisSince(start));
        }
        return library;
    }

    /** The {@code define-library} form for {@code key} in a library file. */
    private Object findDefinition(Source source, String key, Expander errors, Object name) {
        for (Object form : Expander.readForms(source, symbols, false)) {
            if (Syntax.toDatum(form) instanceof SPair p && p.car instanceof SSymbol s && s.toString().equals("define-library") && p.cdr instanceof SPair q &&
                            key(q.car).equals(key)) {
                return form;
            }
        }
        throw errors.syntaxError("no define-library for " + key + " in " + source.getName(), name);
    }

    /** Processes a define-library's declarations, expands and runs its body, and collects its exports. */
    private Library define(String key, Declared declaration, Expander errors) {
        Scope scope = new Scope();
        GlobalEnvironment namespace = new GlobalEnvironment();
        top.registerNamespace(scope, namespace);
        Expander expander = new Expander(symbols, namespace, top, scope, this, declaration.dir());
        List<Object> f = expander.list(declaration.form());
        Deque<Object> declarations = new ArrayDeque<>(f.subList(2, f.size()));
        List<Object[]> exports = new ArrayList<>();
        List<Object> body = new ArrayList<>();
        while (!declarations.isEmpty()) {
            Object declaration1 = declarations.pollFirst();
            List<Object> d = expander.list(declaration1);
            String head = d.isEmpty() || !Expander.isIdentifier(d.get(0)) ? "" : Expander.symbol(d.get(0)).toString();
            List<Object> args = d.subList(Math.min(1, d.size()), d.size());
            switch (head) {
                case "export":
                    for (Object spec : args) {
                        if (Expander.isIdentifier(spec)) {
                            exports.add(new Object[]{spec, Expander.symbol(spec)});
                        } else {
                            List<Object> r = expander.list(spec);
                            if (r.size() != 3 || !Expander.isIdentifier(r.get(0)) || !Expander.symbol(r.get(0)).toString().equals("rename") ||
                                            !Expander.isIdentifier(r.get(1)) || !Expander.isIdentifier(r.get(2))) {
                                throw expander.syntaxError("invalid export spec", spec);
                            }
                            exports.add(new Object[]{r.get(1), Expander.symbol(r.get(2))});
                        }
                    }
                    break;
                case "import":
                    for (Object set : args) {
                        for (Map.Entry<SSymbol, Object> e : importSet(set, expander).entrySet()) {
                            expander.bindImport(e.getKey(), e.getValue(), set);
                        }
                    }
                    break;
                case "begin":
                    body.addAll(args);
                    break;
                case "include":
                case "include-ci":
                    body.addAll(expander.include(declaration1, head.equals("include-ci")));
                    break;
                case "include-library-declarations": {
                    List<Object> read = new ArrayList<>();
                    for (Object file : args) {
                        read.addAll(expander.readFile(Syntax.toDatum(file).toString(), false, declaration1));
                    }
                    for (int i = read.size() - 1; i >= 0; i--) {
                        declarations.addFirst(read.get(i));
                    }
                    break;
                }
                case "cond-expand": {
                    List<Object> chosen = expander.condExpand(declaration1);
                    for (int i = chosen.size() - 1; i >= 0; i--) {
                        declarations.addFirst(chosen.get(i));
                    }
                    break;
                }
                default:
                    throw expander.syntaxError("invalid library declaration in " + key, declaration1);
            }
        }
        // Not closed for the escape analysis (M5-04): a macro the library exports can assign its
        // globals from another unit.
        Ast.Lambda program = expander.expandProgram(body, false);
        if (declaration.form() instanceof Syntax s && s.source != null) {
            program.source = s.source;
        }
        if (!body.isEmpty()) {
            // Most standard libraries only re-export, and have nothing to run.
            runner.run(program, new CompilerState.TopLevel(scope, namespace, false));
        }
        Map<SSymbol, Object> exported = new LinkedHashMap<>();
        for (Object[] export : exports) {
            Object binding = expander.resolveTopLevel(export[0]);
            if (binding instanceof Ast.Var) {
                throw errors.syntaxError("cannot export a local variable", export[0]);
            }
            // R7RS §5.6.1: "an <identifier> names a single binding defined within or imported into
            // the library". A name it does neither for resolves to a cell of its own that nothing defines.
            if (binding instanceof GlobalCell cell && namespace.owns(cell) && !expander.defines(cell)) {
                throw errors.syntaxError("library " + key + " exports " + Expander.symbol(export[0]) + ", which it neither defines nor imports", export[0]);
            }
            Object before = exported.put((SSymbol) export[1], binding);
            if (before != null && before != binding) {
                throw errors.syntaxError("library " + key + " exports " + export[1] + " twice, with different bindings", export[0]);
            }
        }
        return new Library(key, exported);
    }

    /** The names an import set binds (R7RS §5.2): a library's exports through only, except, prefix and rename. */
    @TruffleBoundary
    Map<SSymbol, Object> importSet(Object set, Expander errors) {
        Deque<List<Object>> operations = new ArrayDeque<>();
        Object current = Syntax.toDatum(set);
        while (current instanceof SPair p && p.car instanceof SSymbol op && isImportOperation(op.toString()) && p.cdr instanceof SPair q) {
            operations.push(errors.list(current));
            current = q.car;
        }
        Map<SSymbol, Object> names = new LinkedHashMap<>(instantiate(current, errors).exports());
        while (!operations.isEmpty()) {
            List<Object> op = operations.pop();
            List<Object> args = op.subList(2, op.size());
            switch (op.get(0).toString()) {
                case "only": {
                    Map<SSymbol, Object> kept = new LinkedHashMap<>();
                    for (Object id : args) {
                        if (!(id instanceof SSymbol s) || !names.containsKey(s)) {
                            throw errors.syntaxError("import only: not exported", set);
                        }
                        kept.put(s, names.get(s));
                    }
                    names = kept;
                    break;
                }
                case "except":
                    for (Object id : args) {
                        if (!(id instanceof SSymbol s) || names.remove(s) == null) {
                            throw errors.syntaxError("import except: not exported", set);
                        }
                    }
                    break;
                case "prefix": {
                    if (args.size() != 1 || !(args.get(0) instanceof SSymbol prefix)) {
                        throw errors.syntaxError("import prefix takes one identifier", set);
                    }
                    Map<SSymbol, Object> prefixed = new LinkedHashMap<>();
                    for (Map.Entry<SSymbol, Object> e : names.entrySet()) {
                        prefixed.put(symbols.intern(prefix.toString() + e.getKey()), e.getValue());
                    }
                    names = prefixed;
                    break;
                }
                default: {
                    Map<SSymbol, Object> renamed = new LinkedHashMap<>(names);
                    for (Object pair : args) {
                        List<Object> r = errors.list(pair);
                        if (r.size() != 2 || !(r.get(0) instanceof SSymbol from) || !(r.get(1) instanceof SSymbol) || !names.containsKey(from)) {
                            throw errors.syntaxError("import rename: invalid rename", set);
                        }
                        renamed.remove(from);
                    }
                    for (Object pair : args) {
                        List<Object> r = errors.list(pair);
                        renamed.put((SSymbol) r.get(1), names.get(r.get(0)));
                    }
                    names = renamed;
                    break;
                }
            }
        }
        return names;
    }

    private static boolean isImportOperation(String op) {
        return op.equals("only") || op.equals("except") || op.equals("prefix") || op.equals("rename");
    }
}
