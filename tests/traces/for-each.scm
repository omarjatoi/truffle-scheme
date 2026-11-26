; The procedure for-each calls.
(import (scheme base) (scheme eval) (scheme load) (scheme process-context))
(define (f)
  (list (for-each (lambda (x) (list RAISE)) '(1))))
(define (g) (list (f)))
(g)
