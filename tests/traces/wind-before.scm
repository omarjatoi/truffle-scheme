; dynamic-wind's before thunk.
(import (scheme base) (scheme eval) (scheme load) (scheme process-context))
(define (f)
  (list (dynamic-wind (lambda () (list RAISE)) (lambda () 1) (lambda () 2))))
(define (g) (list (f)))
(g)
