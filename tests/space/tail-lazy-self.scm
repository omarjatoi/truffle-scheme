(import (scheme base) (scheme write))

;; R7RS §3.5: a self tail loop over a lazily built list runs in constant space. Each cell is
;; #(forced? value): forcing one makes the next. Under a small heap this fails if anything keeps the
;; loop's first argument (the head) reachable: the arguments of the call that entered the loop, or
;; the frame the loop's on-stack replacement was entered from.
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
      (let ((v (next s)))
        (walk (cdr v) (- k 1)))))

(display (walk (from 0) 3000000))
(newline)
