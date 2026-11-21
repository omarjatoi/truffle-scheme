;; Complex division and the branch cuts' signs of zero, where chibi-scheme agrees (M12-01;
;; NumbersTest's complexDivisionScalesTheDivisorAndRecoversInfinities and
;; branchCutsAndTheSignOfAZero have the rest). A division by an inexact complex number is C99
;; Annex G.5.1's _Cdivd, which scales the divisor by a power of 2, so an ordinary quotient has the
;; textbook formula's bits, chibi-scheme's; its quotients of huge, tiny and infinite parts aren't
;; chibi-scheme's (0.0, NaN or an infinity there), so they're left out. On log's and atan's cuts the
;; sign of a zero part says the side (R7RS §6.2.6's log range, C99 §7.3.3), and chibi-scheme agrees
;; for log, expt through it, and atan (but it crashes on (atan +2.i)). On sqrt's cut either zero
;; gives the positive imaginary axis (R7RS's "a zero real part and a non-negative imaginary part"),
;; and on asin's and acos's, defined through sqrt, the real number's value; chibi-scheme agrees but
;; at -2, where its asin's imaginary part is an ulp from ours (its sums cancel there). Off the cuts
;; sqrt, asin, acos and atan are Kahan's formulas (M13-02), which round differently from
;; chibi-scheme's in a few of the last bits; these are values where both give the same.
(import (scheme base) (scheme write) (scheme inexact) (scheme complex))

(define (show name x)
  (display name)
  (display " ")
  (write x)
  (newline))

(define-syntax check
  (syntax-rules ()
    ((_ e ...) (begin (show 'e (guard (c (#t 'error)) e)) ...))))

;; / : ordinary quotients keep the textbook formula's bits
(check (/ 3.+4.i 1.+2.i) (/ 1. 3.+4.i) (/ 1+2i 3.+4.i) (/ 2. (make-rectangular 0. 1.)) (/ 1.5+2.5i 0.3-0.7i)
       (/ 1e10+3.i 7.-0.375i) (/ -2.5 1.25+3.75i) (/ (make-rectangular +inf.0 1.) (make-rectangular 1. 1.)))
;; log: the negative real axis, -pi below and pi above
(check (log (make-rectangular -4. -0.)) (log (make-rectangular -4. 0.)) (log (make-rectangular 4. -0.))
       (expt (make-rectangular -4. -0.) 0.5) (expt (make-rectangular -4. 0.) 0.5))
;; sqrt, asin and acos: the same value from either side of the cut, and real arguments
(check (sqrt (make-rectangular -4. 0.)) (sqrt (make-rectangular -4. -0.)) (sqrt (make-rectangular -1. -0.)) (sqrt 3.+4.i)
       (sqrt 3.-4.i) (asin (make-rectangular 2. 0.)) (asin (make-rectangular 2. -0.)) (asin 2.)
       (acos (make-rectangular 2. 0.)) (acos (make-rectangular 2. -0.)) (acos 2.) (asin -2.-0.5i) (asin -3.-1.i)
       (acos -2.-0.5i) (sqrt 1.5+2.5i) (sqrt -1.-2.i))
;; atan: the imaginary axis, the real part's zero telling the side
(check (atan (make-rectangular 0. 2.)) (atan (make-rectangular -0. 2.)) (atan (make-rectangular 0. -2.))
       (atan (make-rectangular -0. -2.)) (atan (make-rectangular 1. 0.)) (real-part (atan 1.+2.i)) (real-part (atan -1.-2.i)))
