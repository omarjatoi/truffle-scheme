; A file that load reads (load/loaded.scm).
; files: loaded.scm
(import (scheme base) (scheme eval) (scheme load) (scheme process-context))
(define (f)
  (list (load "DIR/loaded.scm")))
(define (g) (list (f)))
(g)
