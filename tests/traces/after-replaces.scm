; An after thunk whose error replaces the uncaught error that runs it (design.md D8).
(import (scheme base) (scheme eval) (scheme load) (scheme process-context))
(define (f)
  (list (dynamic-wind (lambda () 0) (lambda () (list (car 'first))) (lambda () (list RAISE)))))
(define (g) (list (f)))
(g)
