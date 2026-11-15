(import (scheme base) (scheme write))

;; Core forms only (M1-05): closures, assignment, internal definitions, rest arguments.
(define (make-counter)
  (define n 0)
  (lambda () (set! n (+ n 1)) n))

(define c (make-counter))
(c)
(c)
(display (c))
(newline)

(define (shared)
  (define v 1)
  (cons (lambda () v) (lambda (x) (set! v x))))
(define p (shared))
((cdr p) 'changed)
(write ((car p)))
(newline)

(display ((lambda (a . rest) (cons a rest)) 1 2 3))
(newline)

(define (collect n acc)
  (if (= n 3) acc (collect (+ n 1) (cons (lambda () n) acc))))
(define (run fs) (if (null? fs) '() (cons ((car fs)) (run (cdr fs)))))
(write (run (collect 0 '())))
(newline)
