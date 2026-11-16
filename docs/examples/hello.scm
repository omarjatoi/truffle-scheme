(import (scheme base) (scheme write) (scheme process-context))

(define args (cdr (command-line)))
(display "Hello, ")
(display (if (null? args) "world" (car args)))
(display "! Arguments: ")
(write args)
(newline)
