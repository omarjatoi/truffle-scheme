;; limits: -Xss256k -Xmx64m
(import (scheme base) (scheme write))

;; R7RS §3.5: apply's procedure and call-with-values' consumer are called in tail position.
(define n 1000000)

(define (a i) (if (>= i n) 'apply (apply a (list (+ i 1)))))
(define (b i) (if (>= i n) 'call-with-values (call-with-values (lambda () (values (+ i 1) 'x)) (lambda (j x) (b j)))))
(define (c i) (if (>= i n) 'apply-variadic (apply c (+ i 1) '())))

(display (a 0)) (newline)
(display (b 0)) (newline)
(display (c 0)) (newline)
