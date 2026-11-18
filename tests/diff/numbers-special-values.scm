;; NaN and -0.0 where R7RS decides the result and chibi-scheme agrees (#242's follow-up; NumbersTest's
;; nanAndNegativeZeroFollowIeee754AndR7rs has the rest). R7RS §6.2.4: an operation with a NaN operand
;; returns NaN, unless the result would be the same with any rational in its place; a NaN compares
;; false to any number; comparisons treat -0.0 as 0. §6.2.6: 0^z and 0.0^z; magnitude is abs for a
;; real; (log -0.0) is -inf.0+πi, which chibi-scheme gets wrong, as (abs -0.0) and (max 1 +nan.0)
;; (tests/fuzz/README.md). A NaN is shown as "NaN": R7RS leaves its sign's notation open.
(import (scheme base) (scheme write) (scheme inexact) (scheme complex))

(define (show name x)
  (display name)
  (display " ")
  (if (and (number? x) (nan? x))
      (display "NaN")
      (write x))
  (newline))

(define-syntax check
  (syntax-rules ()
    ((_ e ...) (begin (show 'e (guard (c (#t 'error)) e)) ...))))

;; expt: a NaN base or power gives NaN; any number to a zero power is 1
(check (expt +nan.0 0.5) (expt +nan.0 2.5) (expt +nan.0 -0.5) (expt +nan.0 3) (expt +nan.0 1/2)
       (expt +nan.0 0) (expt +nan.0 0.) (expt 0. +nan.0) (expt 0 +nan.0) (expt 2. +nan.0)
       (expt -inf.0 0.) (expt +inf.0 0.) (expt -1. 0.) (expt -1 0.) (expt -0. 0.))

;; expt of a zero base
(check (expt 0. 0.5) (expt -0. 0.5) (expt -0. 2.5) (expt -0. 3) (expt 0. 0) (expt 0 0.) (expt 0. 0.)
       (expt 0 0) (expt 0 2) (expt 0. 2))

;; predicates and comparisons
(check (positive? +nan.0) (negative? +nan.0) (zero? +nan.0) (zero? -0.0) (positive? -0.0)
       (negative? -0.0) (= +nan.0 +nan.0) (< 1 +nan.0) (> +nan.0 1) (<= +nan.0 +nan.0) (= -0.0 0)
       (= -0.0 0.0) (< -0.0 0.0) (eqv? -0.0 0.0))

;; max and min of a NaN as the first argument (chibi-scheme drops a later one)
(check (max +nan.0 1) (min +nan.0 1/2) (max 3.9 4) (min 1 2.))

;; exact, magnitude, angle, log, sqrt
(check (exact +nan.0) (exact +inf.0) (exact -0.0) (magnitude -0.0) (magnitude +nan.0) (magnitude -2.5)
       (abs +nan.0) (angle -0.0) (angle 0.0) (angle +nan.0) (angle -1.0) (log +nan.0) (log 0.0)
       (sqrt -0.0) (sqrt +nan.0))

;; rounding, rationalize, make-polar, negation
(check (round -0.4) (round -0.0) (truncate -0.5) (ceiling -0.5) (floor +nan.0) (round +nan.0)
       (rationalize +nan.0 1) (rationalize -0. 1/10) (make-polar 2 -0.) (- 0.0) (- -0.0) (* -1 0.)
       (+ -0.0 -0.0) (- -0.0 0.0) (atan -0.0 -1) (atan 0.0 -0.0) (atan -0.0 1))
