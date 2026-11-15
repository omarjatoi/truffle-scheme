;; limits: -Xss256k -Xmx512m
(import (scheme base) (scheme write))

;; 1,000,000-deep non-tail recursion.
(define (count i) (if (= i 1000000) 0 (+ 1 (count (+ i 1)))))
(display (count 0))
(newline)

;; Building a list non-tail-recursively, then walking it non-tail-recursively.
(define (build i) (if (= i 1000000) '() (cons i (build (+ i 1)))))
(define (sum l) (if (null? l) 0 (+ (car l) (sum (cdr l)))))
(display (sum (build 0)))
(newline)
