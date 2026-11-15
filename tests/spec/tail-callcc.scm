;; limits: -Xss256k -Xmx64m
(import (scheme base) (scheme write))

(define n 1000000)

;; call/cc in tail position; its receiver tail-calls the loop.
(define (a i) (if (< i n) (call/cc (lambda (k) (a (+ i 1)))) 'a-done))

;; Invoking a continuation each iteration, from a call/cc in argument position.
(define (b i) (if (< i n) (b (call/cc (lambda (k) (k (+ i 1))))) 'b-done))

;; Re-entering one continuation a million times, invoking it in tail position.
(define count 0)
(define saved #f)
(define (c)
  (call/cc (lambda (k) (set! saved k)))
  (set! count (+ count 1))
  (if (< count n) (saved #f) 'c-done))

(display (a 0)) (newline)
(display (b 0)) (newline)
(display (c)) (newline)
