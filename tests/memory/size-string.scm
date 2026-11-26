(import (scheme base) (scheme write))

;; Strings and bytevectors longer than a Java array can be, made and appended: errors, raised
;; before allocating (design.md D8, "Running out of memory"); then one uncaught.
(write (guard (e (#t (error-object? e))) (make-string (expt 2 31) #\a)))
(newline)
(write (guard (e (#t (error-object? e))) (make-bytevector (expt 2 31) 0)))
(newline)
(write (guard (e (#t (error-object? e))) (make-string 3000000000)))
(newline)
(make-bytevector 3000000000)
