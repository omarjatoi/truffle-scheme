;; The prelude (design.md D5): syntax and procedures written in Scheme on top of
;; (r7rs %primitives), which the standard libraries import and re-export.
(define-library (r7rs %prelude)
  (import (r7rs %primitives))
  (export
   ;; syntax.scm (R7RS §7.3), and the auxiliary syntax its literals refer to
   let let* letrec and or cond case when unless do let-values let*-values define-values case-lambda
   delay delay-force else =>
   ;; lists.scm
   list caar cadr cdar cddr caddr cdddr length reverse memq memv assq assv map for-each
   ;; control.scm
   dynamic-wind call-with-values %rewind %receive %continue
   ;; lazy.scm
   make-promise promise? force
   ;; exceptions.scm, parameters.scm, records.scm
   with-exception-handler raise raise-continuable error guard make-parameter parameterize
   define-record-type)
  (include "syntax.scm" "lists.scm" "control.scm" "lazy.scm" "exceptions.scm" "parameters.scm"
           "records.scm"))
