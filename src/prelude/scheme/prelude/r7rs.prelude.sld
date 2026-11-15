;; The prelude (design.md D5): procedures written in Scheme on top of (r7rs %primitives), which the
;; standard libraries import and re-export. Its procedures use core forms only.
(define-library (r7rs %prelude)
  (import (r7rs %primitives))
  (export list caar cadr cdar cddr caddr cdddr length reverse dynamic-wind %rewind %receive
          %continue)
  (include "lists.scm" "control.scm"))
