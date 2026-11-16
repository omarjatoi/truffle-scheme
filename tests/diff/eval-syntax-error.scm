(import (scheme base) (scheme write))

;; syntax-error (R7RS §4.3.3): expanding R7RS's simple-let example with a non-identifier is a
;; syntax error, so the program stops with an error status before printing anything.
(define-syntax simple-let
  (syntax-rules ()
    ((_ (head ... ((x . y) val) . tail)
        body1 body2 ...)
     (syntax-error
      "expected an identifier but got"
      (x . y)))
    ((_ ((name val) ...) body1 body2 ...)
     ((lambda (name ...) body1 body2 ...)
      val ...))))

(write (simple-let (((a . b) 1)) a))
(newline)
(display "not reached")
(newline)
