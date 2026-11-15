(import (scheme base) (scheme write))

;; Self tail calls loop; mutual tail calls go through the trampoline (M1-05).
(define (loop i acc)
  (if (= i 1000000) acc (loop (+ i 1) (+ acc i))))
(display (loop 0 0))
(newline)

(define (ev? n) (if (= n 0) #t (od? (- n 1))))
(define (od? n) (if (= n 0) #f (ev? (- n 1))))
(write (ev? 100001))
(newline)

(display (* 99999999999 99999999999))
(newline)
(display (- 0.5 2))
(newline)
