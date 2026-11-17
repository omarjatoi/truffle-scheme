(import (scheme base) (scheme write))

;; R7RS §3.5: a named let entered from the body of a let whose variable holds the head runs in
;; constant space. Entering the named let is a tail call of the let's body (§3.5), so h is dead
;; while it loops; so is the variable of the let in the loop's body once the loop goes round.
(define (from n) (vector #f n))

(define (next s)
  (if (vector-ref s 0)
      (vector-ref s 1)
      (let ((v (cons (vector-ref s 1) (vector #f (+ 1 (vector-ref s 1))))))
        (vector-set! s 0 #t)
        (vector-set! s 1 v)
        v)))

(define (run n)
  (let ((h (from 0)))
    (let loop ((s h) (k n))
      (if (= k 0)
          (car (next s))
          (let ((v (next s)))
            (loop (cdr v) (- k 1)))))))

(display (run 3000000))
(newline)
