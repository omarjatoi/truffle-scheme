; A procedure that eval's code calls.
(import (scheme base) (scheme eval) (scheme load) (scheme process-context))
(define (h) (list RAISE))
(define (f)
  (list (eval (list h) (environment '(scheme base)))))
(define (g) (list (f)))
(g)
