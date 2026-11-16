(import (scheme base) (scheme write))
(define (square x)
  (let ((y (* x x)))
    y))
;; square's caller is weighted, a closure that captures n, called by map: a procedure of its own,
;; whatever the compiler does with loops (plan.md M7-02), with a captured variable to show (M6-05).
(define (sum-squares n)
  (let ((weighted (lambda (i) (* n (square i)))))
    (apply + (map weighted '(0 1 2 3 4)))))
(display (sum-squares 5))
(newline)
