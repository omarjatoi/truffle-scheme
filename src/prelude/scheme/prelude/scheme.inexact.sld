;; A standard library (R7RS §5.6, design.md D7). Names that nothing defines yet are exported as
;; unbound cells, which later tasks fill in.
(define-library (scheme inexact)
  (import (r7rs %primitives) (r7rs %prelude))
  (export
          acos asin atan cos exp finite? infinite? log nan? sin sqrt tan))
