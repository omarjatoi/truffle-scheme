package scheme.runtime;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.source.Source;

import scheme.runtime.value.SClosure;
import scheme.runtime.value.SEnvironment;

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

    /** The context's interaction environment (R7RS §6.12): the one programs run in. */
    SEnvironment interactionEnvironment(SchemeContext context);

    /**
     * An immutable environment importing the import sets (R7RS §6.12, design.md D7), given as data.
     * With {@code syntaxOnly}, only their syntactic keywords (for {@code null-environment}). Errors,
     * such as a library that isn't found, are raisable {@link SchemeError}s.
     */
    SEnvironment environment(SchemeContext context, Object[] importSets, boolean syntaxOnly);

    /**
     * {@code eval} (R7RS §6.12, design.md D6): compiles the datum in the environment into a
     * procedure of no arguments, for {@code eval} to tail-call (D5). Syntax errors in the datum are
     * raisable {@link SchemeError}s, not parse errors.
     */
    SClosure compileEval(SchemeLanguage language, SchemeContext context, Object datum, SEnvironment environment);
}
