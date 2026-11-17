(import (scheme base) (scheme write))

;; R7RS §3.5: mutual tail calls over a lazily built list run in constant space, entered from the
;; body of a let whose variable holds the head. The let's body is in tail position (§3.5), so the
;; call replaces the let's frame: neither h nor the first call's arguments may stay reachable.
(define (from n) (vector #f n))

(define (next s)
  (if (vector-ref s 0)
      (vector-ref s 1)
      (let ((v (cons (vector-ref s 1) (vector #f (+ 1 (vector-ref s 1))))))
        (vector-set! s 0 #t)
        (vector-set! s 1 v)
        v)))

(define (t1 s k) (if (= k 0) (car (next s)) (t2 (cdr (next s)) (- k 1))))
(define (t2 s k) (if (= k 0) (car (next s)) (t1 (cdr (next s)) (- k 1))))

(display (let ((h (from 0))) (t1 h 3000000)))
(newline)
