; The procedure apply calls.
(import (scheme base) (scheme eval) (scheme load) (scheme process-context))
(define (f)
  (list (apply (lambda (x) (list RAISE)) '(1))))
(define (g) (list (f)))
(g)
