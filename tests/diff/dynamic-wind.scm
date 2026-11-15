(import (scheme base) (scheme write))

;; M2-04: dynamic-wind with escapes and re-entry (R7RS §6.10's example), inside procedures.

(define (example)
  (define path '())
  (define c #f)
  (define (add s) (set! path (cons s path)))
  (dynamic-wind
    (lambda () (add 'connect))
    (lambda () (add (call-with-current-continuation (lambda (c0) (set! c c0) 'talk1))))
    (lambda () (add 'disconnect)))
  (if (< (length path) 4) (c 'talk2) (reverse path)))
(write (example))
(newline)

(define (escape)
  (call/cc
   (lambda (k)
     (dynamic-wind
       (lambda () (display "a1 "))
       (lambda ()
         (dynamic-wind (lambda () (display "b1 ")) (lambda () (k 'escaped)) (lambda () (display "b2 "))))
       (lambda () (display "a2 "))))))
(write (escape))
(newline)

(define (reenter)
  (define k #f)
  (define n 0)
  (dynamic-wind
    (lambda () (display "in "))
    (lambda () (call/cc (lambda (c) (set! k c))))
    (lambda () (display "out ")))
  (set! n (+ n 1))
  (if (< n 3) (k 'again) (newline)))
(reenter)
