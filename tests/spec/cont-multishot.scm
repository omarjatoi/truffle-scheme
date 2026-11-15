(import (scheme base) (scheme write))

;; Each re-entry resumes copies of the captured frames (AGENTS.md rule 3).
(define (collect)
  (define saved #f)
  (define results '())
  (define v (list 'a (call/cc (lambda (k) (set! saved k) 0)) 'c))
  (set! results (cons v results))
  (if (< (length results) 4) (saved (length results)) (reverse results)))
(display (collect))
(newline)

;; A variable assigned with set! is one location shared by every re-entry (design.md D2);
;; one bound once per re-entry is not.
(define (shared)
  (define x 0)
  (define k #f)
  (define y (call/cc (lambda (c) (set! k c) 0)))
  (set! x (+ x 1))
  (if (< x 3) (k (* x 10)) (list x y)))
(display (shared))
(newline)

;; One continuation re-entered a thousand times.
(define k3 #f)
(define total 0)
(define (sum)
  (define v (call/cc (lambda (k) (set! k3 k) 0)))
  (set! total (+ total v))
  (if (< v 1000) (k3 (+ v 1)) total))
(display (sum))
(newline)

;; Re-entering a continuation captured inside a procedure that has returned twice already.
(define k4 #f)
(define (inner) (call/cc (lambda (k) (set! k4 k) 1)))
(define (outer) (* 10 (inner)))
(define (run)
  (define calls 0)
  (define v (outer))
  (display v)
  (newline)
  (set! calls (+ calls 1))
  (if (< calls 3) (k4 (+ calls 1)) 'done))
(run)
