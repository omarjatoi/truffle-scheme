(import (scheme base) (scheme lazy) (scheme write))

;; R7RS §3.5 and §4.2.5: traversing a stream of promises with a tail loop runs in constant space
;; (SRFI 45's traversal). Each forced promise keeps its value, the next pair, so keeping the first
;; promise reachable keeps every pair forced since.
(define (ints n) (delay (cons n (ints (+ n 1)))))

(define (traverse s k)
  (if (= k 0)
      (car (force s))
      (traverse (cdr (force s)) (- k 1))))

(display (traverse (ints 0) 3000000))
(newline)
