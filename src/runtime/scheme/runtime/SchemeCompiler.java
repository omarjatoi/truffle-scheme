package scheme.runtime;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.source.Source;

/**
 * The front end, provided by the scheme.compiler project through {@link java.util.ServiceLoader}:
 * the runtime holds the language class, which the Bytecode DSL root needs, and sits below the
 * compiler.
 */
public interface SchemeCompiler {

    /**
     * Sets up a new context once its primitives are defined: instantiates the standard libraries and
     * the interaction environment that programs run in (design.md D7).
     */
    void initializeContext(SchemeLanguage language, SchemeContext context);

    /** Compiles a program into one root (design.md D6), without its prompt. */
    CallTarget compileProgram(SchemeLanguage language, SchemeContext context, Source source);
}
