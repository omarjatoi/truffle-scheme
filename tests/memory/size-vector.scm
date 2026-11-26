(import (scheme base) (scheme write))

;; A vector longer than a Java array can be: an error, raised before allocating, which a handler
;; catches (design.md D8, "Running out of memory"); then the same, uncaught.
(write (guard (e (#t (error-object? e))) (make-vector (expt 10 10))))
(newline)
(make-vector (expt 10 10) 0)
