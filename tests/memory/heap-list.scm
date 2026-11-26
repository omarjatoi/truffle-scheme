(import (scheme base) (scheme write))

;; A program that keeps what it allocates until the heap is full (design.md D8, "Running out of
;; memory"); //tests:memory runs it under a 64 MB heap.
(display "start")
(newline)
(define (grow l) (grow (cons (make-vector 1000 0) l)))
(grow '())
