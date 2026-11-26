; call-with-values's producer.
(import (scheme base) (scheme eval) (scheme load) (scheme process-context))
(define (f)
  (list (call-with-values (lambda () (list RAISE)) list)))
(define (g) (list (f)))
(g)
