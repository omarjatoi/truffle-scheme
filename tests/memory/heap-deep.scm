(import (scheme base) (scheme write))

;; A non-tail recursion without end: its frames spill to the heap (design.md "Stack-depth spill")
;; until the heap is full, never the Java stack (design.md D8, "Running out of memory");
;; //tests:memory runs it under a 64 MB heap.
(display "start")
(newline)
(define (deep n) (+ 1 (deep (+ n 1))))
(deep 0)
