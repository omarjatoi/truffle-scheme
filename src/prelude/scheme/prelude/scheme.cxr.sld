;; A standard library (R7RS §5.6, design.md D7). Names that nothing defines yet are exported as
;; unbound cells, which later tasks fill in.
(define-library (scheme cxr)
  (import (r7rs %primitives) (r7rs %prelude))
  (export
          caaar caadr cadar caddr cdaar cdadr cddar cdddr caaaar caaadr caadar caaddr cadaar
          cadadr caddar cadddr cdaaar cdaadr cdadar cdaddr cddaar cddadr cdddar cddddr))
