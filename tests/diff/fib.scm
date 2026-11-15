(import (scheme base) (scheme write))

;; M1-06 vertical slice: core forms only (define, lambda, if) and the M1 primitives.
(define (fib n)
  (if (< n 2)
      n
      (+ (fib (- n 1)) (fib (- n 2)))))

(display (fib 25))
(newline)
