;; A standard library (R7RS §5.6, design.md D7). Names that nothing defines yet are exported as
;; unbound cells, which later tasks fill in.
(define-library (scheme char)
  (import (r7rs %primitives) (r7rs %prelude))
  (export
          char-alphabetic? char-ci<=? char-ci<? char-ci=? char-ci>=? char-ci>? char-downcase
          char-foldcase char-lower-case? char-numeric? char-upcase char-upper-case?
          char-whitespace? digit-value string-ci<=? string-ci<? string-ci=? string-ci>=?
          string-ci>? string-downcase string-foldcase string-upcase))
