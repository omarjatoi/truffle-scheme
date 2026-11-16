;; (exit #f) is an unsuccessful exit; exit from inside a guard body still unwinds through the guard.
(import (scheme base) (scheme write) (scheme process-context))

(dynamic-wind
  (lambda () #f)
  (lambda ()
    (guard (e (#t (display "caught") (newline)))
      (dynamic-wind
        (lambda () #f)
        (lambda () (exit #f))
        (lambda () (display "inner out") (newline)))))
  (lambda () (display "outer out") (newline)))
