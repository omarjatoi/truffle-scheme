;; limits: -Xss256k -Xmx512m
(import (scheme base) (scheme write))

;; equal? on data nested 1,000,000 deep, in the car and in vectors (rule 4).
(define (nest i x) (if (= i 0) x (nest (- i 1) (list x))))
(define (nest-vector i x) (if (= i 0) x (nest-vector (- i 1) (vector 1 x))))
(display (equal? (nest 1000000 'leaf) (nest 1000000 'leaf)))
(newline)
(display (equal? (nest 1000000 'leaf) (nest 1000000 'other)))
(newline)
(display (equal? (nest-vector 1000000 "s") (nest-vector 1000000 "s")))
(newline)

;; equal? terminates on circular data (R7RS §6.1).
(define a '#0=(x y . #0#))
(define b '#1=(x y . #1#))
(define c '#2=(x y x y . #2#))
(define d '#3=(x z . #3#))
(display (list (equal? a b) (equal? a c) (equal? a d)))
(newline)
(define v1 '#4=#(1 #4#))
(define v2 '#5=#(1 #5#))
(display (equal? v1 v2))
(newline)
