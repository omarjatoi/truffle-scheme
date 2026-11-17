(import (scheme base) (scheme write))

;; R7RS §3.5: tail loops over a lazily built list run in constant space when the procedures were
;; called (and, with the JIT, compiled) before, and when they are entered through apply and
;; call/cc. Compiled code works on a copy of a call's argument array, so the callee clearing its
;; own arguments isn't enough: the caller's array, which the call site, trampoline or primitive
;; that made the call holds, must not keep the head either.
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

(define (walk s k)
  (if (= k 0)
      (car (next s))
      (walk (cdr (next s)) (- k 1))))

(define (warm i)
  (when (> i 0)
    (t1 (from 0) 100)
    (walk (from 0) 100)
    (warm (- i 1))))

(warm 20000)

(let* ((a (t1 (from 0) 3000000))
       (b (walk (from 0) 3000000))
       (c (apply t1 (list (from 0) 3000000)))
       (d (call-with-current-continuation (lambda (k) (t1 (from 0) 3000000)))))
  (display (+ a b c d)))
(newline)
