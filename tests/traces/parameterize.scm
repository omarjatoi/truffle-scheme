; parameterize's body.
(import (scheme base) (scheme eval) (scheme load) (scheme process-context))
(define p (make-parameter 0))
(define (f)
  (list (parameterize ((p 1)) (list RAISE))))
(define (g) (list (f)))
(g)
