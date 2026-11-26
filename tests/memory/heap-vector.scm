(import (scheme base) (scheme write))

;; A vector of a length Java's arrays can have, past what the heap can hold: the VM's out of memory,
;; which Truffle reports as a resource exhausted, not a Scheme error (design.md D8, "Running out of
;; memory"). //tests:memory runs it under a 64 MB heap.
(display "start")
(newline)
(define v (make-vector 100000000 0))
(display (vector-length v))
