;; C99 Annex G.6's special values of the complex elementary functions, where chibi-scheme agrees
;; (M13-02; NumbersTest's specialValuesOfTheElementaryFunctions has the whole table). R7RS §6.2.6
;; is silent on infinities and NaNs there, so sqrt, exp, log, asin, acos and atan follow csqrt,
;; cexp, clog, casin, cacos and catan, but on sqrt's, asin's and acos's cuts, where R7RS's text
;; wins. chibi-scheme's own functions give NaN for most of the others, and write a NaN imaginary
;; part as `++nan.0i` (tests/fuzz/README.md), so those are left out.
(import (scheme base) (scheme write) (scheme inexact) (scheme complex))

(define (show name x)
  (display name)
  (display " ")
  (write x)
  (newline))

(define-syntax check
  (syntax-rules ()
    ((_ e ...) (begin (show 'e (guard (c (#t 'error)) e)) ...))))

;; csqrt: x+i∞ is +∞+i∞, and the root of 0 is +0
(check (sqrt (make-rectangular 1. +inf.0)) (sqrt (make-rectangular 0. 0.)))
;; cexp: -∞+iy is +0 cis y, +∞+iy is +∞ cis y, and e^(-0-0i) is 1-0i
(check (exp (make-rectangular -inf.0 1.)) (exp (make-rectangular -inf.0 2.)) (exp (make-rectangular +inf.0 2.))
       (exp (make-rectangular -0. -0.)))
;; clog: the sign of a zero, and the infinities
(check (log (make-rectangular -0. 0.)) (log (make-rectangular 0. -0.)) (log (make-rectangular 1. +inf.0))
       (log (make-rectangular -inf.0 1.)) (log (make-rectangular +inf.0 -1.)) (log (make-rectangular -inf.0 +inf.0))
       (log (make-rectangular +inf.0 +inf.0)))
;; asin and acos: a NaN is NaN, +inf.0 is on the cut (the real number's side, R7RS), and cacos(-0-0i) is π/2+0i
(check (asin +nan.0) (acos +nan.0) (asin +inf.0) (acos +inf.0) (acos (make-rectangular -0. -0.)))
