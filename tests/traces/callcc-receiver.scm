; call/cc's receiver, on top of call/cc's prompt.
(import (scheme base) (scheme eval) (scheme load) (scheme process-context))
(define (f)
  (list (call/cc (lambda (k) (list RAISE)))))
(define (g) (list (f)))
(g)
