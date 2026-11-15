package scheme.runtime;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.source.Source;

/**
 * The front end, provided by the scheme.compiler project through {@link java.util.ServiceLoader}:
 * the runtime holds the language class, which the Bytecode DSL root needs, and sits below the
 * compiler.
 */
public interface SchemeCompiler {

    /** Compiles a program into one root (design.md D6). */
    CallTarget compileProgram(SchemeLanguage language, SchemeContext context, Source source);
}
