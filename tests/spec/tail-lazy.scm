;; limits: -Xss256k -Xmx32m
(import (scheme base) (scheme write))

;; R7RS §3.5: tail calls over a lazily built list run in constant space. Each cell is
;; #(forced? value), and forcing one makes the next, so a loop that walks 3M cells allocates far
;; more than the heap holds: it fails if anything keeps the head reachable while it runs. A self
;; tail loop; mutual tail calls started from the body of a let whose variable holds the head (the
;; let's body is in tail position, so the call replaces the let's frame); a named let entered from
;; such a let.
(define (from n) (vector #f n))

(define (next s)
  (if (vector-ref s 0)
      (vector-ref s 1)
      (let ((v (cons (vector-ref s 1) (vector #f (+ 1 (vector-ref s 1))))))
        (vector-set! s 0 #t)
        (vector-set! s 1 v)
        v)))

(define (walk s k)
  (if (= k 0)
      (car (next s))
      (walk (cdr (next s)) (- k 1))))

(define (t1 s k) (if (= k 0) (car (next s)) (t2 (cdr (next s)) (- k 1))))
(define (t2 s k) (if (= k 0) (car (next s)) (t1 (cdr (next s)) (- k 1))))

(display (walk (from 0) 3000000))
(newline)
(display (let ((h (from 0))) (t1 h 3000000)))
(newline)
(display (let ((h (from 0)))
           (let loop ((s h) (k 3000000))
             (if (= k 0)
                 (car (next s))
                 (loop (cdr (next s)) (- k 1))))))
(newline)
