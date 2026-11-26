; with-exception-handler's handler, for raise-continuable.
(import (scheme base) (scheme eval) (scheme load) (scheme process-context))
(define (f)
  (list (with-exception-handler
          (lambda (e) (list RAISE))
          (lambda () (list (raise-continuable 'c))))))
(define (g) (list (f)))
(g)
