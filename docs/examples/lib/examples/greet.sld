(define-library (examples greet)
  (export greet)
  (import (scheme base))
  (begin
    (define (greet name)
      (string-append "Hello from a library, " name "!"))))
