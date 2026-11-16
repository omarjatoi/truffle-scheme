package scheme.compiler;

/**
 * The core forms (design.md D3). (r7rs %primitives) exports each under its name, and the standard
 * libraries re-export them (D15); in the interaction environment an unbound identifier with a core
 * form's name is that core form.
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
    PROMPT("%prompt");

    final String keyword;

    CoreForm(String keyword) {
        this.keyword = keyword;
    }
}
