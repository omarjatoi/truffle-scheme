(import (scheme base) (scheme write))

;; Backtracking with amb: every choice point is a continuation re-entered on failure.
(define fail-stack '())

(define (fail)
  (define k (car fail-stack))
  (set! fail-stack (cdr fail-stack))
  (k 'retry))

(define (amb choices)
  (call/cc
   (lambda (return)
     (define (try cs)
       (if (null? cs)
           (fail)
           (begin
             (call/cc (lambda (next)
                        (set! fail-stack (cons next fail-stack))
                        (return (car cs))))
             (try (cdr cs)))))
     (try choices))))

(define (require p) (if p #t (fail)))

(define (range lo hi) (if (> lo hi) '() (cons lo (range (+ lo 1) hi))))

(define (triple n)
  (define a (amb (range 1 n)))
  (define b (amb (range a n)))
  (define c (amb (range b n)))
  (require (= (+ (* a a) (* b b)) (* c c)))
  (list a b c))

(define found '())
(define (record! t) (set! found (cons t found)))

(define (all-triples n)
  (call/cc
   (lambda (done)
     (set! found '())
     (set! fail-stack (list (lambda (ignored) (done (reverse found)))))
     (record! (triple n))
     (fail))))

(display (all-triples 20))
(newline)
