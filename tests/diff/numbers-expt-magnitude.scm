;; expt of a negative real base and magnitude, where R7RS and IEEE 754 decide the result and
;; chibi-scheme agrees (#243's follow-ups; NumbersTest's exptOfNegativeBasesAndMagnitudeFollowIeee754
;; has the rest). A real base to an integer-valued power is real (IEEE 754's pow, R7RS §6.2.3; the
;; imaginary part of §6.2.6's e^(z2 log z1) is 0), also to an infinite power, which pow takes for an
;; even integer. Left out: a negative base to a non-integer power, complex here as e^(z2 log z1)
;; and NaN in chibi-scheme, and magnitudes beyond the flonums' squares, +inf.0 or 0.0 in
;; chibi-scheme (tests/fuzz/README.md).
(import (scheme base) (scheme write) (scheme inexact) (scheme complex))

(define (show name x)
  (display name)
  (display " ")
  (write x)
  (newline))

(define-syntax check
  (syntax-rules ()
    ((_ e ...) (begin (show 'e (guard (c (#t 'error)) e)) ...))))

;; a negative real base to an integer-valued flonum power
(check (expt -2. 2.) (expt -2. 3.) (expt -2 2.) (expt -2. -2.) (expt -2. -3.) (expt -1/2 3.) (expt -3 -1.)
       (expt -1.5e-200 3.) (expt -1.5e308 2.) (expt -1. 1.5e300) (expt -2. 1.5e300) (expt -.5 1.5e300) (expt -1.5 4.)
       (real? (expt -7. 5.)))
;; ... to an infinite power
(check (expt -2. +inf.0) (expt -.5 +inf.0) (expt -1. +inf.0) (expt -2. -inf.0) (expt -.5 -inf.0)
       (expt -1 -inf.0) (expt -inf.0 +inf.0) (expt -inf.0 -inf.0) (expt 1. +inf.0) (expt 1 -inf.0))
;; a negative infinite base to an integer power
(check (expt -inf.0 2.) (expt -inf.0 3.) (expt -inf.0 -2.) (expt -inf.0 -3.) (expt -inf.0 3) (expt -inf.0 -4))
;; exact stays exact
(check (expt -2 3) (expt -2 -3) (expt -1/2 3) (expt -2. 3))
;; magnitude
(check (magnitude 3+4i) (magnitude -3/5+4/5i) (magnitude 1+2i) (magnitude 3.+4.i) (magnitude -3.-4.i)
       (magnitude 1.5+2.5i) (magnitude (make-rectangular 0. -0.)) (magnitude (make-rectangular +inf.0 1.))
       (magnitude (make-rectangular 1. -inf.0)) (magnitude (make-rectangular (expt 10 200) (expt 10 200)))
       (magnitude (make-rectangular (* 12345 (expt 10 196)) 1)) (magnitude (make-rectangular (* 3 (expt 10 20)) (* 4 (expt 10 20)))))
