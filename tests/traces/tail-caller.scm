; In tail position: the frame that made the tail call is gone.
(import (scheme base) (scheme eval) (scheme load) (scheme process-context))
(define (leave) RAISE)
(define (f) (list (leave)))
(define (g) (list (f)))
(g)
