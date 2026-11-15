;; limits: -Xss256k -Xmx64m
(import (scheme base) (scheme write))

(define (my-even? n) (if (= n 0) #t (my-odd? (- n 1))))
(define (my-odd? n) (if (= n 0) #f (my-even? (- n 1))))

(display (my-even? 10000000))
(newline)
(display (my-odd? 7777777))
(newline)
