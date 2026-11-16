;; emergency-exit skips the outstanding after-thunks; output written so far still appears.
(import (scheme base) (scheme write) (scheme process-context))

(dynamic-wind
  (lambda () (display "in") (newline))
  (lambda () (emergency-exit 5))
  (lambda () (display "out") (newline)))
(display "not reached")
(newline)
