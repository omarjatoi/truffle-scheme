(import (scheme base) (scheme write))

;; M2-02: call/cc escapes, re-entry and multi-shot, inside procedures (re-entry at top level differs
;; from chibi by design: see design.md D6).

(write (+ 1 (call/cc (lambda (k) (+ 10 (k 5))))))
(newline)

(define (find-neg l)
  (call/cc
   (lambda (return)
     (define (walk l)
       (if (null? l) #f (if (< (car l) 0) (return (car l)) (walk (cdr l)))))
     (walk l))))
(write (list (find-neg '(1 2 -3 4)) (find-neg '(1 2))))
(newline)

(define (reenter)
  (define r #f)
  (define n 0)
  (display (+ 100 (call/cc (lambda (k) (set! r k) 1))))
  (newline)
  (set! n (+ n 1))
  (if (< n 3) (r n) 'done))
(reenter)

(define (operands)
  (define k #f)
  (define once #f)
  (display (+ 1 (* 2 (call/cc (lambda (c) (set! k c) 3)))))
  (newline)
  (if once 'done (begin (set! once #t) (k 10))))
(operands)

(define (make-gen l)
  (define return #f)
  (define walk-k #f)
  (define (walk l)
    (if (null? l)
        (return 'done)
        (begin (call/cc (lambda (resume) (set! walk-k resume) (return (car l))))
               (walk (cdr l)))))
  (lambda ()
    (call/cc (lambda (r) (set! return r) (if walk-k (walk-k #f) (walk l))))))
(define g (make-gen '(a b c)))
(define (take4)
  ;; Internal defines evaluate in order (letrec*); argument order is unspecified in R7RS.
  (define a (g))
  (define b (g))
  (define c (g))
  (define d (g))
  (list a b c d))
(write (take4))
(newline)
