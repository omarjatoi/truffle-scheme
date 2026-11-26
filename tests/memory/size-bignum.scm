(import (scheme base) (scheme read) (scheme write))

;; Exact numbers past BigInteger's range, 2^31 bits: errors, raised before multiplying or
;; allocating (design.md D8, "Running out of memory"): powers, exact decimal notation read by
;; string->number (an error) and by read (a read error); then one uncaught.
(define (caught thunk)
  (guard (e ((read-error? e) 'read-error) ((error-object? e) 'error)) (thunk)))
(write (list (caught (lambda () (expt 3 1500000000)))
             (caught (lambda () (expt 2 2147483647)))
             (caught (lambda () (expt 2/3 -2000000000)))
             (caught (lambda () (string->number "#e1e-1000000000")))
             (caught (lambda () (string->number "#e1e1000000000")))
             (caught (lambda () (string->number "#e1e-3000000000")))
             (caught (lambda () (read (open-input-string "#e1.5e-1000000000"))))
             (string->number "1e3000000000")))
(newline)
(expt 7 (expt 2 30))
