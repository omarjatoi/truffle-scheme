; call-with-values's consumer.
(import (scheme base) (scheme eval) (scheme load) (scheme process-context))
(define (f)
  (list (call-with-values (lambda () 1) (lambda (x) (list RAISE)))))
(define (g) (list (f)))
(g)
