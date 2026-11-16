;; A raise no handler catches ends the program with a nonzero exit status (R7RS §6.11, design.md D8),
;; after a handler that returns from it has run.
(import (scheme base) (scheme write))

(display "before")
(newline)
(with-exception-handler
 (lambda (e) (display "handler saw ") (write e) (newline))
 (lambda () (raise 'boom)))
(display "not reached")
(newline)
