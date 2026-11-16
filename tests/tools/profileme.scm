(import (scheme base) (scheme write))
(define (fib n)
  (if (< n 2)
      n
      (+ (fib (- n 1)) (fib (- n 2)))))
(define (make-table n)
  (let loop ((i 0) (acc '()))
    (if (= i n)
        acc
        (loop (+ i 1) (cons (make-vector 8 i) acc)))))
(define (run-fib k)
  (let loop ((i 0) (total 0))
    (if (= i k)
        total
        (loop (+ i 1) (+ total (fib 22) (length (make-table 100)))))))
(display (run-fib 10))
(newline)
