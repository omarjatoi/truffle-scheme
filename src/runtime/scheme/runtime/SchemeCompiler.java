package scheme.runtime;

import java.util.List;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.source.Source;

import scheme.runtime.value.SClosure;
import scheme.runtime.value.SEnvironment;
import scheme.runtime.value.SSymbol;

/**
 * The front end, provided by the scheme.compiler project through {@link java.util.ServiceLoader}:
 * the runtime holds the language class, which the Bytecode DSL root needs, and sits below the
 * compiler.
 */
public interface SchemeCompiler {

    /**
     * Sets up a new context once its primitives are defined: instantiates the standard libraries and
     * the interaction environment (design.md D7).
     */
    void initializeContext(SchemeLanguage language, SchemeContext context);

    /**
     * Rebinds what the compiler's state took from the context's Env when it was initialized (the
     * library path, design.md D7) to the Env it now has: a context pre-initialized in a native
     * image, taken over by a run ({@code SchemeLanguage.patchContext}, design.md D17).
     */
    void patchContext(SchemeContext context);

    /**
     * Compiles program text into one root (design.md D6), without its prompt: a program (first form
     * {@code import}) in a top level of its own, other text in the interaction environment (D15). An
     * interactive source (REPL input, D17) always runs in the interaction environment, where a
     * leading {@code import} adds to it.
     */
    CallTarget compileProgram(SchemeLanguage language, SchemeContext context, Source source);

    /** The context's interaction environment (R7RS §6.12): the one REPL input runs in (design.md D15). */
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

    /**
     * {@code load} (R7RS §6.13.1, design.md D6): compiles every form of the file's source in the
     * environment into one procedure of no arguments, for {@code load} to tail-call (D5). Like
     * {@link #compileEval}, read and syntax errors are raisable {@link SchemeError}s, and they name
     * the file, as do the compiled code's source sections (D13).
     */
    SClosure compileLoad(SchemeLanguage language, SchemeContext context, Source source, SEnvironment environment);

    /**
     * Code a debugger evaluates in a suspended frame (plan.md M6-05): the source's forms, as a body,
     * compiled into a procedure whose parameters are the frame's variables by name, in the frame's
     * top level ({@code topLevel}, what its root records, {@link SchemeRootNode#getTopLevel}: an
     * isolated program's or a library's, plan.md M7-07), else the interaction environment (design.md
     * D15). A variable with {@code boxed} set is passed as its Box and used as the procedure's own;
     * {@code set!} of any other is a syntax error, since it would change only a copy, and so is
     * {@code set!} of an isolated program's own variables (a library's may be assigned).
     */
    SClosure compileInFrame(SchemeLanguage language, SchemeContext context, Source source, String[] names, boolean[] boxed, Object topLevel);

    /**
     * The names of the interaction environment's bound variables, imported or defined (not syntactic
     * keywords): the top scope's members (design.md D10).
     */
    List<SSymbol> interactionVariables(SchemeContext context);

    /**
     * The cell a name refers to at the interaction environment's top level (possibly unbound), or
     * null if the name is a syntactic keyword there.
     */
    GlobalCell interactionCell(SchemeContext context, SSymbol name);

    /**
     * {@code (define name value)} at the interaction environment's top level: defines, redefines or
     * shadows an import, as the REPL would. False, changing nothing, if the name is a syntactic
     * keyword there.
     */
    boolean interactionDefine(SchemeContext context, SSymbol name, Object value);
}
