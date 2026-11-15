;; exit: 3
(import (scheme base) (scheme process-context) (scheme write))

;; exit runs the outstanding after-thunks, innermost first, then exits with its status.
(dynamic-wind
  (lambda () (display "a-in") (newline))
  (lambda ()
    (dynamic-wind
      (lambda () (display "b-in") (newline))
      (lambda () (exit 3))
      (lambda () (display "b-out") (newline))))
  (lambda () (display "a-out") (newline)))
(display "not reached")
(newline)
