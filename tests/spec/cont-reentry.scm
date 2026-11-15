(import (scheme base) (scheme write))

;; Pending operands survive re-entry: (+ 1 (* 2 [])).
(define r #f)
(define n 0)
(define (run)
  (display (+ 1 (* 2 (call/cc (lambda (k) (set! r k) 3)))))
  (newline)
  (set! n (+ n 1))
  (if (< n 3) (r (* n 10)) 'done))
(run)

;; Arguments evaluated before the capture keep their values on re-entry.
(define (args)
  (define r2 #f)
  (define m 0)
  (display (list 'a (call/cc (lambda (k) (set! r2 k) 'first)) 'c))
  (newline)
  (set! m (+ m 1))
  (if (< m 3) (r2 m) 'done))
(args)

;; design.md D6: re-entering a continuation captured in one top-level form carries on
;; through the forms after it.
(define r3 #f)
(define p 0)
(display (list 'top (call/cc (lambda (k) (set! r3 k) 0))))
(newline)
(set! p (+ p 1))
(if (< p 3) (r3 p))
(display "after")
(newline)
