(import (scheme base) (scheme write))

;; R7RS §3.5: a tail loop over a lazily built list runs in constant space when its first argument
;; came back from a recursion deep enough to spill (design.md "Continuations"): the frame that
;; makes the loop's call is resumed from the heap with the head, so neither the prompt's driver nor
;; the resumed frame's continuation root may keep the value it was resumed with.
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

(define (build n)
  (if (= n 0)
      (from 0)
      (car (list (build (- n 1))))))

(display (walk (build 20000) 3000000))
(newline)
