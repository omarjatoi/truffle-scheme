;; assq and assv of quoted alists (plan.md M9-05): compiled to one comparison operation, with
;; the primitives' values.
(import (scheme base) (scheme write))

(define (op-code op)
  (let ((e (assq op '((add . 1) (sub . 2) (mul . 3) (div . 4)))))
    (if e (cdr e) 'unknown)))

(define (digit-name d)
  (assv d '((0 zero) (1 one) (2 two) (2.5 two-and-a-half) (#\3 three))))

(write (map op-code '(add sub mul div mod)))
(newline)
(write (map digit-name (list 0 1 2 2.5 #\3 4 1.0)))
(newline)
(write (assv (expt 10 20) '((1 . a) (100000000000000000000 . big))))
(newline)
(write (list (assq 'b '((a . 1) (b . 2) (b . 3))) (assq 'c '())))
(newline)
