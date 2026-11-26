; with-exception-handler's handler, for raise.
(import (scheme base) (scheme eval) (scheme load) (scheme process-context))
(define (f)
  (list (with-exception-handler
          (lambda (e) (list RAISE))
          (lambda () (list (raise 'c))))))
(define (g) (list (f)))
(g)
