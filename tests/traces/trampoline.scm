; A primitive that a trampoline runs: apply's tail call of it.
(import (scheme base) (scheme eval) (scheme load) (scheme process-context))
(define (f)
  (list (apply PRIM (list ARG))))
(define (g) (list (f)))
(g)
