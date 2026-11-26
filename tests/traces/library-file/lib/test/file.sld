(define-library (test file)
  (import (scheme base) (scheme process-context))
  (begin
    (define (h) (list RAISE))
    (list (h))))
