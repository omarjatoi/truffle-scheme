package scheme.compiler;

/** The core forms (design.md D3), each bound to its name unless shadowed. */
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
    PROMPT("%prompt");

    final String keyword;

    CoreForm(String keyword) {
        this.keyword = keyword;
    }
}
