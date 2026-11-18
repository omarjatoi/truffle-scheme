;; A real and a complex number, the square roots of exact numbers beyond the flonums' range, and
;; (expt 1. +nan.0), where R7RS, C99 Annex G.5.1 and IEEE 754 decide the result and chibi-scheme
;; agrees (M11-01; NumbersTest's realAndComplexArithmeticTakeEachPart and
;; sqrtOfAnExactNumberIsTheNearestFlonum have the rest). A real number is taken with each part of a
;; complex number on its own: x(u+iv) = xu + i(xv), x ± (u+iv) = (x ± u) + i(±v), with their signs
;; of zero. Left out: (u+iv)/x for an x whose square overflows or is 0.0, and a complex number
;; minus a real equal to its real part, where chibi-scheme's real part is -0.0; mixed exactness,
;; where chibi-scheme keeps an exact part (design.md D1 makes both inexact); and the square roots
;; of tiny exact numbers, 0.0 in chibi-scheme. chibi-scheme crashes on (- 1/2 1+2i), a ratio minus an
;; exact complex number, and its - of a real and a complex number negates the complex number's real
;; part in place, the literal 1. of (make-rectangular 1. -0.) too, so those parts are computed.
(import (scheme base) (scheme write) (scheme inexact) (scheme complex))

(define (show name x)
  (display name)
  (display " ")
  (write x)
  (newline))

(define-syntax check
  (syntax-rules ()
    ((_ e ...) (begin (show 'e (guard (c (#t 'error)) e)) ...))))

;; * : each part times the real, an infinite part included
(check (* 2. (make-rectangular +inf.0 1.)) (* (make-rectangular +inf.0 1.) 2.) (* 2 (make-rectangular +inf.0 1.))
       (* 2. (make-rectangular 1. -inf.0)) (* +inf.0 (make-rectangular 1. 2.)) (* 1.5e200 (make-rectangular 1.5e200 1.5))
       (* 2. (make-rectangular 1.5 2.5)))
;; ... with its signs of zero
(check (* 2. (make-rectangular 1. -0.)) (* -2. (make-rectangular 1. 0.)) (* -2. (make-rectangular -0. 1.))
       (* -0. (make-rectangular 1. 2.)))
;; / : by a real, and a real by a complex number
(check (/ (make-rectangular +inf.0 1.) 2.) (/ (make-rectangular 1. -0.) 2.) (/ 1. (make-rectangular 1. 0.))
       (/ 1. (make-rectangular 2. -0.)) (/ 2. (make-rectangular 0. 1.)) (/ 1. (make-rectangular 3. 4.)))
;; + and - : the imaginary part kept or negated
(check (+ 1. (make-rectangular 1. -0.)) (+ (make-rectangular 1. -0.) 1.) (+ 1 (make-rectangular 1. -0.))
       (- 1. (make-rectangular (inexact 1) -0.)) (- 1. (make-rectangular (inexact 1) 0.))
       (- 2. (make-rectangular (inexact 1) -0.)) (- (make-rectangular (inexact 1) -0.) 2.))
;; exact stays exact
(check (* 1/2 1+2i) (/ 1+2i 2) (/ 2 1+i) (/ 1 1+2i) (- 1 1+2i))
;; sqrt of an exact number beyond the flonums' range, whose root is inside it
(check (sqrt (* 2 (expt 10 400))) (sqrt (expt 10 401)) (sqrt (+ (* 2 (expt 10 400)) 1)) (sqrt (* 3 (expt 2 2100)))
       (sqrt 2) (sqrt 1/3) (sqrt (expt 10 400)))
;; pow(+1, y) is 1 for every y (IEEE 754), a NaN too
(check (expt 1. +nan.0) (expt 1 +nan.0) (expt -1. +nan.0) (expt 2. +nan.0))
