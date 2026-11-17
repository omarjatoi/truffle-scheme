package scheme.compiler;

/**
 * The core forms (design.md D3). (r7rs %primitives) exports each under its name, and the standard
 * libraries re-export them (D15); in the interaction environment an unbound identifier with a core
 * form's name is that core form.
 *
 * <p>The auxiliary syntax of R7RS §4.2 and §4.3.2 ({@code else}, {@code =>}, {@code ...},
 * {@code _}) is bound here too, as keywords that are an error anywhere but where a macro looks for
 * them: a syntax-rules literal matches them by binding, and {@code ...} and {@code _} in patterns
 * are recognized by name. Without a binding, a library that exports them would export a variable
 * nothing defines (design.md D18).
 */
enum CoreForm {
    QUOTE("quote"),
    QUASIQUOTE("quasiquote"),
    UNQUOTE("unquote"),
    UNQUOTE_SPLICING("unquote-splicing"),
    LAMBDA("lambda"),
    IF("if"),
    SET("set!"),
    DEFINE("define"),
    BEGIN("begin"),
    LETREC_STAR("letrec*"),
    DEFINE_SYNTAX("define-syntax"),
    LET_SYNTAX("let-syntax"),
    LETREC_SYNTAX("letrec-syntax"),
    SYNTAX_RULES("syntax-rules"),
    INCLUDE("include"),
    INCLUDE_CI("include-ci"),
    COND_EXPAND("cond-expand"),
    DEFINE_LIBRARY("define-library"),
    IMPORT("import"),
    SYNTAX_ERROR("syntax-error"),
    PROMPT("%prompt"),
    ELSE("else"),
    ARROW("=>"),
    ELLIPSIS("..."),
    UNDERSCORE("_");

    final String keyword;

    CoreForm(String keyword) {
        this.keyword = keyword;
    }
}
