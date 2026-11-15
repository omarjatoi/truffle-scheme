;; The prelude (design.md D5): syntax and procedures written in Scheme on top of
;; (r7rs %primitives), which the standard libraries import and re-export. Each fan-out area adds
;; its exports and Scheme code through its own declarations file (AGENTS.md "Areas"); everything
;; is one namespace, so areas can use each other's definitions.
(define-library (r7rs %prelude)
  (import (r7rs %primitives))
  (export
   ;; syntax.scm (R7RS §7.3), and the auxiliary syntax its literals refer to
   let let* letrec and or cond case when unless do let-values let*-values define-values case-lambda
   delay delay-force else =>
   ;; lazy.scm
   make-promise promise? force
   ;; records.scm
   define-record-type)
  (include "syntax.scm")
  (include-library-declarations
   "numbers.decls.scm" "chars-strings.decls.scm" "lists-vectors.decls.scm" "bytevectors.decls.scm"
   "control.decls.scm" "io.decls.scm" "eval.decls.scm" "process-time.decls.scm")
  (include "lazy.scm" "records.scm"))
