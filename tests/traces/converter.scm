; A parameter's converter, which parameterize calls.
(import (scheme base) (scheme eval) (scheme load) (scheme process-context))
(define p (make-parameter 0 (lambda (x) (if (= x 1) (list RAISE) x))))
(define (f)
  (list (parameterize ((p 1)) 0)))
(define (g) (list (f)))
(g)
