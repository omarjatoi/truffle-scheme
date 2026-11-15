;; limits: -Xss256k -Xmx64m
(import (scheme base) (scheme write))

(define n 1000000)

;; Both branches of if.
(define (a i) (if (< i n) (if (= 0 0) (a (+ i 1)) 'no) 'a-done))
;; The last expression of begin.
(define (b i) (if (< i n) (begin 1 2 (b (+ i 1))) 'b-done))
;; A body with internal definitions; a call through a local procedure.
(define (c i)
  (define j (+ i 1))
  (define (again) (c j))
  (if (< i n) (again) 'c-done))
;; A letrec* body.
(define (d i) (letrec* ((next (+ i 1))) (if (< i n) (d next) 'd-done)))
;; A procedure passed as an argument.
(define (e i k) (if (< i n) (k (+ i 1) k) 'e-done))
;; An immediately applied lambda.
(define (f i) (if (< i n) ((lambda (j) (f j)) (+ i 1)) 'f-done))
;; A closure returned by a procedure, made afresh each time.
(define (make-step) (lambda (i) (if (< i n) ((make-step) (+ i 1)) 'g-done)))
;; A variable assigned with set!.
(define h #f)
(set! h (lambda (i) (if (< i n) (h (+ i 1)) 'h-done)))

(display (a 0)) (newline)
(display (b 0)) (newline)
(display (c 0)) (newline)
(display (d 0)) (newline)
(display (e 0 e)) (newline)
(display (f 0)) (newline)
(display ((make-step) 0)) (newline)
(display (h 0)) (newline)
