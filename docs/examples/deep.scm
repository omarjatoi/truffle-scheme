(import (scheme base) (scheme write))

;; A million calls deep, none of them a tail call: the deep recursion moves to the heap.
(define (depth n)
  (if (= n 0)
      0
      (+ 1 (depth (- n 1)))))

(display (depth 1000000))
(newline)
