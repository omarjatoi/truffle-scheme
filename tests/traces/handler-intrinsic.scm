; with-exception-handler's handler, for a primitive's error (car's, an operation in its frame).
(import (scheme base) (scheme eval) (scheme load) (scheme process-context))
(define (f)
  (list (with-exception-handler
          (lambda (e) (list RAISE))
          (lambda () (list (car 'y))))))
(define (g) (list (f)))
(g)
