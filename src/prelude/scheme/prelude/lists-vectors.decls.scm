;; The lists and vectors area's declarations in (r7rs %prelude) (AGENTS.md "Areas"): what it
;; exports, and its Scheme file. The standard libraries re-export these names.
(export
 list caar cadr cdar cddr caddr cdddr map for-each
 member assoc vector-map vector-for-each
 caaar caadr cadar cdaar cdadr cddar caaaar caaadr caadar caaddr cadaar cadadr caddar cadddr
 cdaaar cdaadr cdadar cdaddr cddaar cddadr cdddar cddddr)
(include "lists-vectors.scm")
