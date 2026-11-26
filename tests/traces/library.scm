; The body of a library the program defines.
(define-library (test body)
  (import (scheme base) (scheme process-context))
  (begin
    (define (h) (list RAISE))
    (list (h))))
(import (test body))
