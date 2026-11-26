; dynamic-wind's thunk.
(import (scheme base) (scheme eval) (scheme load) (scheme process-context))
(define (f)
  (list (dynamic-wind (lambda () 0) (lambda () (list RAISE)) (lambda () 2))))
(define (g) (list (f)))
(g)
