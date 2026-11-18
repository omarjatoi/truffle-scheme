package scheme.compiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

import scheme.reader.Scope;
import scheme.reader.ScopeSet;
import scheme.runtime.GlobalCell;
import scheme.runtime.GlobalEnvironment;
import scheme.runtime.SchemeCompiler;
import scheme.runtime.SchemeContext;
import scheme.runtime.SchemeError;
import scheme.runtime.SchemeLanguage;
import scheme.runtime.SchemeLogs;
import scheme.runtime.SchemeRootNode;
import scheme.runtime.SchemeRootNodeGen;
import scheme.runtime.printer.Printer;
import scheme.runtime.value.SClosure;
import scheme.runtime.value.SEnvironment;
import scheme.runtime.value.SSymbol;

/** The front end (a {@link SchemeCompiler} service): read syntax objects, expand, generate one program root (design.md D6). */
public final class ProgramCompiler implements SchemeCompiler {

    private static final Object[] NO_CAPTURES = new Object[0];

    /** What the interaction environment imports. */
    private static final String INTERACTION_IMPORTS = """
                    (import (r7rs %primitives) (scheme base) (scheme case-lambda) (scheme char) (scheme complex)
                            (scheme cxr) (scheme eval) (scheme file) (scheme inexact) (scheme lazy) (scheme load)
                            (scheme process-context) (scheme read) (scheme repl) (scheme time) (scheme write)
                            (scheme r5rs))
                    """;

    /**
     * Instantiates the standard libraries and makes the interaction environment import them, or
     * restores both from the {@link PreludeImage} (design.md D13).
     */
    @Override
    public void initializeContext(SchemeLanguage language, SchemeContext context) {
        long start = System.nanoTime();
        PreludeImage image = PreludeImage.get(context);
        if (image != null) {
            PreludeImage.Restored restored = image.restore(language, context, state -> newLibraries(language, context, state));
            context.compilerState = restored.state();
            for (SchemeRootNode body : restored.bodies()) {
                language.withPrompt(body.getCallTarget()).call();
            }
            if (SchemeLogs.on(SchemeLogs.PRELUDE)) {
                SchemeLogs.PRELUDE.fine("restored the standard libraries from the prelude image in " + SchemeLogs.millisSince(start));
            }
            return;
        }
        CompilerState state = new CompilerState();
        context.compilerState = state;
        state.libraries = newLibraries(language, context, state);
        boolean build = PreludeImage.shouldBuild(context);
        if (build) {
            state.recordedBodies = new ArrayList<>();
        }
        Source imports = Source.newBuilder(SchemeLanguage.ID, INTERACTION_IMPORTS, "interaction-environment").internal(true).build();
        language.withPrompt(compile(language, context, imports, false)).call();
        if (SchemeLogs.on(SchemeLogs.PRELUDE)) {
            SchemeLogs.PRELUDE.fine("expanded and ran the standard libraries in " + SchemeLogs.millisSince(start));
        }
        if (build) {
            List<CompilerState.LibraryBody> bodies = state.recordedBodies;
            state.recordedBodies = null;
            PreludeImage.build(context, state, bodies);
        }
    }

    @Override
    public void patchContext(SchemeContext context) {
        ((CompilerState) context.compilerState).libraries.patch(context.getEnv(), context.getEnv().getOptions().get(SchemeLanguage.LIBRARY_PATH));
    }

    private static Libraries newLibraries(SchemeLanguage language, SchemeContext context, CompilerState state) {
        return new Libraries(context.symbols, state.top, context.globals, (body, topLevel) -> {
            if (state.recordedBodies != null) {
                state.recordedBodies.add(new CompilerState.LibraryBody(body, topLevel));
            }
            language.withPrompt(generate(language, body, topLevel)).call();
        }, context.getEnv(), context.getEnv().getOptions().get(SchemeLanguage.LIBRARY_PATH));
    }

    /**
     * A program (R7RS §5.1: text whose first form is {@code import}) runs in a top level of its own,
     * which has only its imports and its definitions; any other text, and REPL input (an interactive
     * source, design.md D17) whatever its first form, runs in the interaction environment (D15).
     */
    @Override
    public CallTarget compileProgram(SchemeLanguage language, SchemeContext context, Source source) {
        return compile(language, context, source, !source.isInteractive());
    }

    private static CallTarget compile(SchemeLanguage language, SchemeContext context, Source source, boolean mayBeProgram) {
        CompilerState state = (CompilerState) context.compilerState;
        List<Object> forms = Expander.readForms(source, context.symbols, false);
        SourceDir dir = new SourceDir.Files(context.getEnv(), null).dirOf(source);
        Ast.Lambda program;
        CompilerState.TopLevel topLevel = null;
        if (mayBeProgram && Expander.isProgram(forms)) {
            Scope scope = new Scope();
            GlobalEnvironment namespace = new GlobalEnvironment();
            state.top.registerNamespace(scope, namespace);
            topLevel = new CompilerState.TopLevel(scope, namespace, true);
            program = new Expander(context.symbols, namespace, state.top, scope, state.libraries, dir).expandIsolatedProgram(forms);
        } else {
            Expander expander = new Expander(context.symbols, state.interaction, state.top, state.interactionScope, state.libraries, dir);
            program = source.isInteractive() ? expander.expandInteractive(forms) : expander.expandProgram(forms, false);
        }
        if (source.hasCharacters() && source.getLength() > 0) {
            program.source = source.createSection(0, source.getLength());
        }
        return generate(language, program, topLevel);
    }

    @Override
    public SEnvironment interactionEnvironment(SchemeContext context) {
        return ((CompilerState) context.compilerState).interactionEnvironment;
    }

    @Override
    @TruffleBoundary
    public SEnvironment environment(SchemeContext context, Object[] importSets, boolean syntaxOnly) {
        CompilerState state = (CompilerState) context.compilerState;
        StringBuilder key = new StringBuilder(syntaxOnly ? "syntax" : "all");
        for (Object set : importSets) {
            key.append(' ').append(Printer.print(set, Printer.Mode.WRITE));
        }
        SEnvironment environment = state.environments.get(key.toString());
        if (environment != null) {
            return environment;
        }
        // A fresh top-level scope and namespace, like a library's (design.md D7).
        Scope scope = new Scope();
        GlobalEnvironment namespace = new GlobalEnvironment();
        Expander expander = new Expander(context.symbols, namespace, state.top, scope, state.libraries, new SourceDir.Files(context.getEnv(), null));
        Map<SSymbol, Object> bindings = new LinkedHashMap<>();
        try {
            for (Object set : importSets) {
                for (Map.Entry<SSymbol, Object> e : state.libraries.importSet(set, expander).entrySet()) {
                    if (!syntaxOnly || !(e.getValue() instanceof GlobalCell)) {
                        bindings.put(e.getKey(), e.getValue());
                    }
                }
            }
        } catch (SchemeError e) {
            throw raisable(e);
        }
        for (Map.Entry<SSymbol, Object> e : bindings.entrySet()) {
            state.top.bind(e.getKey(), ScopeSet.EMPTY.add(scope), e.getValue());
        }
        state.top.registerNamespace(scope, namespace);
        environment = new SEnvironment(new CompilerState.Environment(scope, namespace, false));
        state.environments.put(key.toString(), environment);
        return environment;
    }

    @Override
    @TruffleBoundary
    public SClosure compileEval(SchemeLanguage language, SchemeContext context, Object datum, SEnvironment environment) {
        CompilerState state = (CompilerState) context.compilerState;
        CompilerState.Environment env = (CompilerState.Environment) environment.state;
        Expander expander = new Expander(context.symbols, env.namespace(), state.top, env.scope(), state.libraries, new SourceDir.Files(context.getEnv(), null));
        Ast.Lambda thunk;
        try {
            thunk = expander.expandEval(datum, env.mutable());
        } catch (SchemeError e) {
            throw raisable(e);
        }
        thunk.source = callerLocation();
        return new SClosure(generate(language, thunk, env.topLevel()), NO_CAPTURES);
    }

    /**
     * Where eval was called: the location of the innermost Scheme frame on the stack, the call
     * eval runs in (a primitive runs in its caller's frame, M5-09), or null if that frame has
     * none or eval was called from the host. The code eval compiles from a datum has no source of
     * its own (R7RS §6.12), so tools show it there: its root, statements and calls are located at
     * the call (design.md D13, "Tools").
     */
    private static SourceSection callerLocation() {
        SourceSection[] location = new SourceSection[1];
        Truffle.getRuntime().iterateFrames(frame -> {
            BytecodeNode bytecode = BytecodeNode.get(frame);
            if (bytecode == null) {
                // A primitive's root (apply's target, say) or a prompt's: its caller called eval.
                return null;
            }
            SourceSection section = bytecode.getSourceLocation(frame);
            location[0] = section != null && section.isAvailable() ? section : null;
            return frame;
        });
        return location[0];
    }

    @Override
    @TruffleBoundary
    public SClosure compileLoad(SchemeLanguage language, SchemeContext context, Source source, SEnvironment environment) {
        CompilerState state = (CompilerState) context.compilerState;
        CompilerState.Environment env = (CompilerState.Environment) environment.state;
        // Files the loaded file includes are relative to it, as for a program.
        SourceDir dir = new SourceDir.Files(context.getEnv(), null).dirOf(source);
        Expander expander = new Expander(context.symbols, env.namespace(), state.top, env.scope(), state.libraries, dir);
        Ast.Lambda thunk;
        try {
            thunk = expander.expandLoad(Expander.readForms(source, context.symbols, false), env.mutable());
        } catch (SchemeError e) {
            throw raisable(e);
        }
        if (source.hasCharacters() && source.getLength() > 0) {
            thunk.source = source.createSection(0, source.getLength());
        }
        return new SClosure(generate(language, thunk, env.topLevel()), NO_CAPTURES);
    }

    @Override
    @TruffleBoundary
    public SClosure compileInFrame(SchemeLanguage language, SchemeContext context, Source source, String[] names, boolean[] boxed, Object topLevel) {
        CompilerState state = (CompilerState) context.compilerState;
        SourceDir dir = new SourceDir.Files(context.getEnv(), null);
        // An isolated program's, a library's or an immutable environment's frame: its own top level.
        CompilerState.TopLevel unit = (CompilerState.TopLevel) topLevel;
        Expander expander = unit != null
                        ? new Expander(context.symbols, unit.namespace(), state.top, unit.scope(), state.libraries, dir)
                        : new Expander(context.symbols, state.interaction, state.top, state.interactionScope, state.libraries, dir);
        List<SSymbol> symbols = new ArrayList<>(names.length);
        for (String name : names) {
            symbols.add(context.symbols.intern(name));
        }
        Ast.Lambda procedure = expander.expandInFrame(Expander.readForms(source, context.symbols, false), symbols, boxed, unit != null && unit.closed());
        return new SClosure(generate(language, procedure, topLevel), NO_CAPTURES);
    }

    /**
     * A syntax error at run time, from {@code eval}, {@code load} or {@code environment}, is an
     * error object that Scheme handlers see (R7RS §6.12), not a parse error of the program (design.md
     * D13). One from {@code syntax-error} keeps its message and irritants.
     */
    private static SchemeError raisable(SchemeError e) {
        if (!e.isSyntaxError()) {
            return e;
        }
        return e.getErrorObject() != null ? new SchemeError(e.getErrorObject()) : new SchemeError(e.getMessage());
    }

    // The interaction environment as interop's top scope (design.md D10, D15).

    @Override
    @TruffleBoundary
    public List<SSymbol> interactionVariables(SchemeContext context) {
        CompilerState state = (CompilerState) context.compilerState;
        Set<SSymbol> names = new LinkedHashSet<>(state.top.entries().keySet());
        for (GlobalCell cell : state.interaction.cells()) {
            names.add(cell.name);
        }
        List<SSymbol> variables = new ArrayList<>();
        for (SSymbol name : names) {
            if (resolveInteraction(state, name) instanceof GlobalCell cell && cell.valueOrNull() != null) {
                variables.add(name);
            }
        }
        return variables;
    }

    @Override
    @TruffleBoundary
    public GlobalCell interactionCell(SchemeContext context, SSymbol name) {
        return resolveInteraction((CompilerState) context.compilerState, name) instanceof GlobalCell cell ? cell : null;
    }

    @Override
    @TruffleBoundary
    public boolean interactionDefine(SchemeContext context, SSymbol name, Object value) {
        CompilerState state = (CompilerState) context.compilerState;
        if (!(resolveInteraction(state, name) instanceof GlobalCell current)) {
            return false;
        }
        // As the expander's top-level define does: the interaction environment's own cell, bound
        // for its top-level scope if the name referred to something else (an import).
        GlobalCell cell = state.interaction.cell(name);
        if (current != cell) {
            state.top.bind(name, ScopeSet.EMPTY.add(state.interactionScope), cell);
        }
        cell.define(value);
        return true;
    }

    /**
     * What the name refers to written at the interaction environment's top level, as the expander
     * resolves it: a binding for the top-level scope, else a core form by name, else the interaction
     * environment's own cell.
     */
    private static Object resolveInteraction(CompilerState state, SSymbol name) {
        ScopeSet scopes = ScopeSet.EMPTY.add(state.interactionScope);
        SyntacticEnvironment.Entry best = null;
        for (SyntacticEnvironment.Entry entry : state.top.candidates(name)) {
            if (entry.scopes().isSubsetOf(scopes) && (best == null || entry.scopes().size() > best.scopes().size())) {
                best = entry;
            }
        }
        if (best != null) {
            return best.binding();
        }
        for (CoreForm form : CoreForm.values()) {
            if (form.keyword.equals(name.toString())) {
                return form;
            }
        }
        return state.interaction.cell(name);
    }

    /** {@code topLevel}: what each root records as the top level it was compiled in, or null for the interaction environment's. */
    private static RootCallTarget generate(SchemeLanguage language, Ast.Lambda program, Object topLevel) {
        SchemeRootNode[] root = new SchemeRootNode[1];
        // With source information, so guest stack traces and tools see file, line and column (D13).
        BytecodeRootNodes<SchemeRootNode> nodes = SchemeRootNodeGen.create(language, BytecodeConfig.WITH_SOURCE, b -> root[0] = new CodeGenerator(b, topLevel).generate(program));
        // Every root of the unit is loaded (its call target made, which is when instruments see a
        // root) with the outermost, not when its first closure is made: a debugger resolves a line
        // breakpoint to the nearest statement among the roots loaded so far, so a line in a lambda
        // would otherwise resolve first to the statement that encloses the lambda's definition, and
        // stop there (design.md D13, "Tools"). Outermost first, so the innermost root that holds a
        // line resolves it last.
        for (SchemeRootNode node : nodes.getNodes()) {
            node.getCallTarget();
        }
        return root[0].getCallTarget();
    }
}
