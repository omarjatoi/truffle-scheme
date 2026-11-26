; No continuations: the baseline the other cases compare with.
(import (scheme base) (scheme eval) (scheme load) (scheme process-context))
(define (f) (list RAISE))
(define (g) (list (f)))
(g)
