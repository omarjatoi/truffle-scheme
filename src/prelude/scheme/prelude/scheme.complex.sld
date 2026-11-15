;; A standard library (R7RS §5.6, design.md D7). Names that nothing defines yet are exported as
;; unbound cells, which later tasks fill in.
(define-library (scheme complex)
  (import (r7rs %primitives) (r7rs %prelude))
  (export
          angle imag-part magnitude make-polar make-rectangular real-part))
