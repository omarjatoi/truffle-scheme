;; limits: -Xss256k -Xmx512m
(import (scheme base) (scheme write))

;; A continuation 100,000 frames deep, re-entered twice.
(define saved #f)
(define n 0)
(define (deep i)
  (if (= i 100000)
      (call/cc (lambda (k) (set! saved k) 0))
      (+ 1 (deep (+ i 1)))))
(define (run)
  (display (deep 0))
  (newline)
  (set! n (+ n 1))
  (if (< n 3) (saved (* n 10)) 'done))
(run)

;; An escape from 1,000,000 frames deep.
(display (call/cc (lambda (k)
  (define (down i) (if (= i 1000000) (k 'escaped) (+ 1 (down (+ i 1)))))
  (down 0))))
(newline)
