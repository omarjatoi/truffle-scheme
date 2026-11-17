;; A program that imports only (scheme r5rs) (R7RS Appendix A). Erratum 22: "the (scheme r5rs)
;; library should export syntax-rules, else, ..., => and _"; Appendix A: "the exact and inexact
;; procedures appear under their R5RS names inexact->exact and exact->inexact respectively".
(import (scheme r5rs))

(define-syntax swap!
  (syntax-rules ()
    ((_ a b) (let ((tmp a)) (set! a b) (set! b tmp)))))

(define-syntax my-list
  (syntax-rules ()
    ((_ x ...) (list x ...))))

(define x 1)
(define y 2)
(swap! x y)
(write (list x y))
(newline)
(write (my-list 1 2 3))
(newline)
(write (cond ((assv 2 '((1 . a) (2 . b))) => cdr) (else 'none)))
(newline)
(write (case 3 ((1 2) 'low) (else 'high)))
(newline)
(write (list (exact->inexact 1/4) (inexact->exact 0.5) (eq? exact->inexact exact->inexact)))
(newline)

;; The same names in the R5RS environment (R7RS §6.12; chibi's null-environment has neither
;; syntax-rules nor else, so EvalTest covers that one).
(write (eval '(exact->inexact 1/8) (scheme-report-environment 5)))
(newline)
