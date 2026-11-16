;; exit runs the outstanding after-thunks, innermost first, each in its own dynamic environment,
;; then exits with its status; command-line, environment variables, time and features.
(import (scheme base) (scheme write) (scheme process-context) (scheme time) (scheme file))

(define (show . xs) (for-each display xs) (newline))

(show "command-line: " (length (command-line)) " " (string? (car (command-line))))
(show "PATH is a string: " (string? (get-environment-variable "PATH")))
(show "no such variable: " (get-environment-variable "TRUFFLE_SCHEME_NO_SUCH_VARIABLE"))
(show "r7rs feature: " (and (memq 'r7rs (features)) #t))
(show "time: " (inexact? (current-second)) " " (exact-integer? (current-jiffy))
      " " (exact-integer? (jiffies-per-second)))
(show "file-exists?: " (file-exists? ".") " " (file-exists? " no such file "))

(define p (make-parameter 'outer))

(dynamic-wind
  (lambda () (show "a-in"))
  (lambda ()
    (parameterize ((p 'inner))
      (dynamic-wind
        (lambda () (show "b-in " (p)))
        (lambda ()
          (with-exception-handler
            (lambda (e) (show "handler: " e) (exit 3))
            (lambda () (raise 'boom))))
        (lambda () (show "b-out " (p))))))
  (lambda () (show "a-out " (p))))
(show "not reached")
