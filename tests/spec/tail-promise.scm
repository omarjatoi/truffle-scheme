;; limits: -Xss256k -Xmx64m
;; R7RS §4.2.5: delay-force lets "iterative lazy algorithms ... be written in constant space"
;; (design.md D9: §7.3's space-safe reference implementation). Each loop below forces a chain of
;; a million promises; one that kept them all, or forced them recursively, would run out of heap
;; or stack.
(import (scheme base) (scheme write) (scheme lazy))

;; §4.2.5's loop.
(define (loop n)
  (delay-force (if (= n 0) (delay 'done) (loop (- n 1)))))
(write (force (loop 1000000)))
(newline)

;; §4.2.5's stream-filter, filtering a million elements before the first match.
(define (from n) (delay (cons n (from (+ n 1)))))
(define (head stream) (car (force stream)))
(define (tail stream) (cdr (force stream)))
(define (stream-filter p? s)
  (delay-force
   (if (null? (force s))
       (delay '())
       (let ((h (car (force s)))
             (t (cdr (force s))))
         (if (p? h)
             (delay (cons h (stream-filter p? t)))
             (stream-filter p? t))))))
(write (head (stream-filter (lambda (n) (= n 1000000)) (from 0))))
(newline)
(write (head (tail (tail (stream-filter odd? (from 0))))))
(newline)

;; SRFI 45's stream-ref: a delay-force chain through a filtered stream.
(define (stream-ref s index)
  (delay-force
   (if (null? (force s))
       (delay 'error)
       (if (= index 0)
           (delay (car (force s)))
           (stream-ref (cdr (force s)) (- index 1))))))
(write (force (stream-ref (stream-filter even? (from 0)) 500000)))
(newline)

;; make-promise of a value at the end of a chain.
(define (countdown n)
  (delay-force (if (= n 0) (make-promise 'ok) (countdown (- n 1)))))
(write (force (countdown 1000000)))
(newline)
