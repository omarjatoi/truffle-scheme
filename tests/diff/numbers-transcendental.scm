;; Transcendental functions of flonums whose results here (fdlibm's, design.md D1) are the nearest
;; flonums to the true values, as chibi-scheme's (its C library's) are. R7RS §6.2.6 doesn't fix
;; their accuracy; NumbersTest has the cases where fdlibm's result is an ulp off, such as (exp 1),
;; and where chibi-scheme's is (on macOS, (asin 0.5) and (acos 0.5); (tan 1)).
(import (scheme base) (scheme write) (scheme inexact))

(define (show name x)
  (display name)
  (display " ")
  (write x)
  (newline))

(show "(exp 0.5)" (exp 0.5))
(show "(exp 2)" (exp 2))
(show "(exp 10)" (exp 10))
(show "(log 10)" (log 10))
(show "(log 100 10)" (log 100 10))
(show "(sin 1)" (sin 1))
(show "(cos 1)" (cos 1))
(show "(sin 1e22)" (sin 1e22))
(show "(cos 1e22)" (cos 1e22))
(show "(atan 1)" (atan 1))
(show "(atan 1 1)" (atan 1 1))
(show "(atan -3 -4)" (atan -3 -4))
(show "(sqrt 2.)" (sqrt 2.))
(show "(expt 2.5 3.7)" (expt 2.5 3.7))
(show "(expt 1.1 100)" (expt 1.1 100))
