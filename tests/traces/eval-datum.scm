; eval's code itself: a datum, with no source.
(import (scheme base) (scheme eval) (scheme load) (scheme process-context))
(define (f)
  (list (eval '(list RAISE) (environment '(scheme base) '(scheme process-context)))))
(define (g) (list (f)))
(g)
