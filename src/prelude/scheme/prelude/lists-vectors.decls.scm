;; The lists and vectors area's declarations in (r7rs %prelude) (AGENTS.md "Areas"): what it
;; exports, and its Scheme file. The standard libraries re-export these names.
(export
 list caar cadr cdar cddr caddr cdddr length reverse memq memv assq assv map for-each)
(include "lists-vectors.scm")
