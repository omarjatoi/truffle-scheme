; After an escape through call/cc, in the frame it returned to.
(import (scheme base) (scheme eval) (scheme load) (scheme process-context))
(define (f)
  (list (call/cc (lambda (k) (k 1)))))
(define (g) (list (f) RAISE))
(g)
