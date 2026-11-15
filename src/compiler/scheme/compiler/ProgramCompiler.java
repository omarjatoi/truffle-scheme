package scheme.compiler;

import java.util.ArrayList;
import java.util.List;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.source.Source;

import scheme.reader.Reader;
import scheme.runtime.SchemeCompiler;
import scheme.runtime.SchemeContext;
import scheme.runtime.SchemeLanguage;
import scheme.runtime.SchemeRootNode;
import scheme.runtime.SchemeRootNodeGen;
import scheme.runtime.value.SConstant;

/** The front end (a {@link SchemeCompiler} service): read syntax objects, expand, generate one program root (design.md D6). */
public final class ProgramCompiler implements SchemeCompiler {

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
        Reader reader = Reader.forSource(source, context.symbols, true);
        List<Object> forms = new ArrayList<>();
        for (Object datum = reader.readSyntax(); datum != SConstant.EOF; datum = reader.readSyntax()) {
            forms.add(datum);
        }
        SourceDir dir = new SourceDir.Files(context.getEnv(), null).dirOf(source);
        Expander expander = new Expander(context.symbols, state.interaction, state.top, state.interactionScope, state.libraries, dir);
        return generate(language, expander.expandProgram(forms));
    }

    private static CallTarget generate(SchemeLanguage language, Ast.Lambda program) {
        SchemeRootNode[] root = new SchemeRootNode[1];
        SchemeRootNodeGen.create(language, BytecodeConfig.DEFAULT, b -> root[0] = new CodeGenerator(b).generate(program));
        return root[0].getCallTarget();
    }
}
