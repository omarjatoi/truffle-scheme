; The procedure map calls.
(import (scheme base) (scheme eval) (scheme load) (scheme process-context))
(define (f)
  (list (map (lambda (x) (list RAISE)) '(1))))
(define (g) (list (f)))
(g)
