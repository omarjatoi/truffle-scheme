; dynamic-wind's after thunk, as the thunk returns.
(import (scheme base) (scheme eval) (scheme load) (scheme process-context))
(define (f)
  (list (dynamic-wind (lambda () 0) (lambda () 1) (lambda () (list RAISE)))))
(define (g) (list (f)))
(g)
