;; A standard library (R7RS §5.6, design.md D7). Names that nothing defines yet are exported as
;; unbound cells, which later tasks fill in.
(define-library (scheme file)
  (import (r7rs %primitives) (r7rs %prelude))
  (export
          call-with-input-file call-with-output-file delete-file file-exists?
          open-binary-input-file open-binary-output-file open-input-file open-output-file
          with-input-from-file with-output-to-file))
