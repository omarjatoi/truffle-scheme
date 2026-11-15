package scheme.compiler;

/**
 * A macro transformer (design.md D3): maps a macro use to its expansion. {@code syntax-rules}
 * (M3-03) makes these; tests can install Java ones with
 * {@link SyntacticEnvironment#defineSyntax}.
 */
@FunctionalInterface
public interface Transformer {

    /**
     * @param form the macro use, a syntax object whose scopes already include the use's fresh
     *            macro-introduction scope (Flatt 2016)
     * @param expander for comparing identifiers ({@link Expander#freeIdentifierEquals})
     * @return the expansion: syntax objects, or plain data. Identifiers the transformer makes up
     *         (plain symbols, or syntax with the macro definition's scopes) are introduced by the
     *         macro, and the expander keeps them apart from the use site's identifiers.
     */
    Object transform(Object form, Expander expander);
}
