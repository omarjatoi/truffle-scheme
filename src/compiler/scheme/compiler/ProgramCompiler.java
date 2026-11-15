package scheme.compiler;

import java.util.ArrayList;
import java.util.List;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.source.Source;

import scheme.reader.Reader;
import scheme.reader.StringCharSource;
import scheme.runtime.SchemeCompiler;
import scheme.runtime.SchemeContext;
import scheme.runtime.SchemeLanguage;
import scheme.runtime.SchemeRootNode;
import scheme.runtime.SchemeRootNodeGen;
import scheme.runtime.value.SConstant;

/** The front end (a {@link SchemeCompiler} service): read, analyze, generate one program root (design.md D6). */
public final class ProgramCompiler implements SchemeCompiler {

    @Override
    public CallTarget compileProgram(SchemeLanguage language, SchemeContext context, Source source) {
        Reader reader = new Reader(new StringCharSource(source.getCharacters().toString()), context.symbols, true);
        List<Object> forms = new ArrayList<>();
        for (Object datum = reader.read(); datum != SConstant.EOF; datum = reader.read()) {
            forms.add(datum);
        }
        Ast.Lambda program = new Analyzer(context.symbols, context.globals).analyzeProgram(forms);
        SchemeRootNode[] root = new SchemeRootNode[1];
        SchemeRootNodeGen.create(language, BytecodeConfig.DEFAULT, b -> root[0] = new CodeGenerator(b).generate(program));
        return root[0].getCallTarget();
    }
}
