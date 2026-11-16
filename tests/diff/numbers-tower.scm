(import (scheme base) (scheme complex) (scheme inexact) (scheme write))

;; M4-01: the numeric tower against chibi: exact rationals, bignums, complex numbers, the integer
;; division families, rounding, exactness, and number syntax.
(define (show . xs) (for-each (lambda (x) (write x) (display " ")) xs) (newline))

(show (/ 6 4) (+ 1/3 2/3) (* 2/3 3/2) (- 1/2 1/2) (/ 7 -14) (exact .25) (inexact 1/3))
(show (expt 2 100) (* 99999999999 99999999999) (- (expt 2 62) (expt 2 63)) (quotient (expt 10 30) 7))
(show (+ 1+2i 3-4i) (* 1+2i 3-4i) (/ 1+2i 3-4i) (- 1/2+1/3i) (make-rectangular 1 2) (real-part 3-4i) (imag-part 3-4i))
(show (magnitude 3+4i) (magnitude -5) (angle -1) (sqrt -4) (sqrt 16/9) (call-with-values (lambda () (exact-integer-sqrt 17)) list))
(show (call-with-values (lambda () (floor/ 7 -2)) list) (call-with-values (lambda () (truncate/ 7 -2)) list) (modulo -7 2) (remainder -7 2) (quotient -7 2))
(show (floor-quotient 7 -2) (floor-remainder 7 -2) (truncate-quotient -7 2) (truncate-remainder -7 2))
(show (gcd 12 -18) (lcm 4 6) (gcd) (lcm) (numerator 6/4) (denominator 6/4) (numerator 0.5) (denominator 0.5))
(show (floor -7/2) (ceiling -7/2) (truncate -7/2) (round -7/2) (round 5/2) (round 2.5) (round -2.5) (floor 2.7))
(show (max 1 2 3) (min 1 2.0 3) (max 1/2 1/3) (abs -7/3) (square 1/2) (expt 2 -2) (expt 2/3 3) (expt 4 1/2))
(show (rationalize 1/3 1/100) (rationalize (exact .3) 1/10) (exact (floor 2.5)) (exact-integer? 5))
(show (number->string 255 16) (number->string -1/3 2) (string->number "#xff") (string->number "1/2") (string->number "-1.5e2") (string->number "abc"))
(show (= 1/2 0.5) (< 1 3/2 2) (= 1 1.0 1+0i) (exact? 1/2) (inexact? 0.5) (integer? 2.0) (rational? 1/2) (real? 1+2i) (zero? 0+0i))
