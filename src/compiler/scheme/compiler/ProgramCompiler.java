package scheme.compiler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.source.Source;

import scheme.reader.Scope;
import scheme.reader.ScopeSet;
import scheme.runtime.GlobalCell;
import scheme.runtime.GlobalEnvironment;
import scheme.runtime.SchemeCompiler;
import scheme.runtime.SchemeContext;
import scheme.runtime.SchemeError;
import scheme.runtime.SchemeLanguage;
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

    @Override
    public void initializeContext(SchemeLanguage language, SchemeContext context) {
        CompilerState state = new CompilerState();
        context.compilerState = state;
        state.libraries = new Libraries(context.symbols, state.top, context.globals, body -> language.withPrompt(generate(language, body)).call(),
                        context.getEnv(), context.getEnv().getOptions().get(SchemeLanguage.LIBRARY_PATH));
        Source imports = Source.newBuilder(SchemeLanguage.ID, INTERACTION_IMPORTS, "interaction-environment").internal(true).build();
        language.withPrompt(compileProgram(language, context, imports)).call();
    }

    @Override
    public CallTarget compileProgram(SchemeLanguage language, SchemeContext context, Source source) {
        CompilerState state = (CompilerState) context.compilerState;
        List<Object> forms = Expander.readForms(source, context.symbols, false);
        SourceDir dir = new SourceDir.Files(context.getEnv(), null).dirOf(source);
        Expander expander = new Expander(context.symbols, state.interaction, state.top, state.interactionScope, state.libraries, dir);
        Ast.Lambda program = expander.expandProgram(forms);
        if (source.hasCharacters() && source.getLength() > 0) {
            program.source = source.createSection(0, source.getLength());
        }
        return generate(language, program);
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
        return new SClosure(generate(language, thunk), NO_CAPTURES);
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
        return new SClosure(generate(language, thunk), NO_CAPTURES);
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

    private static RootCallTarget generate(SchemeLanguage language, Ast.Lambda program) {
        SchemeRootNode[] root = new SchemeRootNode[1];
        // With source information, so guest stack traces and tools see file, line and column (D13).
        SchemeRootNodeGen.create(language, BytecodeConfig.WITH_SOURCE, b -> root[0] = new CodeGenerator(b).generate(program));
        return root[0].getCallTarget();
    }
}
