;; R7RS §6.2.6: "It is an error if any argument of / other than the first is an exact zero",
;; whatever the other arguments are (the §6 audit's follow-up, design.md D9). chibi-scheme raises
;; for these too; (/ 0. 0) it returns 0.0, so NumbersTest covers that one. An inexact zero divides
;; as IEEE does.
(import (scheme base) (scheme write))

(define (try name thunk)
  (display name)
  (display ": ")
  (display (guard (e ((error-object? e) 'error) (#t 'raised)) (thunk)))
  (newline))

(define (divide a b) (/ a b))

(try "(/ 3 0)" (lambda () (/ 3 0)))
(try "(/ 1. 0)" (lambda () (/ 1. 0)))
(try "(/ 1 2. 0)" (lambda () (/ 1 2. 0)))
(try "(/ 0)" (lambda () (/ 0)))
(try "at a site that has divided flonums"
     (lambda () (list (divide 3. 2.) (divide 1. 4.) (divide 1. 0))))
(try "(/ 1 0.)" (lambda () (/ 1 0.)))
(try "(/ -1. 0.)" (lambda () (/ -1. 0.)))
(try "(/ 0 2.)" (lambda () (/ 0 2.)))
