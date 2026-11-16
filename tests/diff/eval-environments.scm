(import (scheme base) (scheme write) (scheme eval) (scheme repl) (scheme inexact) (scheme r5rs))

;; eval in environments made by environment (R7RS §6.12).
(write (eval '(* 7 3) (environment '(scheme base))))
(newline)
(write (eval '(let loop ((i 0) (acc '())) (if (= i 3) (reverse acc) (loop (+ i 1) (cons i acc))))
             (environment '(scheme base))))
(newline)
(write (eval '(+ (expt 2 10) (inexact (sin 0))) (environment '(scheme base) '(scheme inexact))))
(newline)
(write (eval '(kons 1 2) (environment '(rename (only (scheme base) cons) (cons kons)))))
(newline)
(write (eval '(b:list 1 (b:+ 1 1)) (environment '(prefix (only (scheme base) list +) b:))))
(newline)

;; Errors in evaluated code reach handlers like any other. (Before the R5RS environments: after
;; chibi makes one, its guard no longer catches a raise from eval'd code.)
(write (guard (e ((symbol? e) e))
         (eval '(raise 'oops) (environment '(scheme base)))))
(newline)
(write (guard (e ((error-object? e) 'error))
         (eval '(vector-ref (vector 1 2) 5) (environment '(scheme base)))))
(newline)

;; The R5RS environments.
(write (eval '(* 7 3) (scheme-report-environment 5)))
(newline)
(write ((eval '(lambda (f x) (f x x)) (null-environment 5)) + 10))
(newline)

;; The interaction environment: definitions persist and the program sees them.
(eval '(define answer 42) (interaction-environment))
(write (eval 'answer (interaction-environment)))
(newline)
(eval '(define (twice f x) (f (f x))) (interaction-environment))
(write (eval '(twice (lambda (n) (* n n)) 3) (interaction-environment)))
(newline)


;; eval's result is in tail position: this loop runs in constant space.
(define env (interaction-environment))
(eval '(define (count-down n)
         (if (= n 0) 'done (eval (list 'count-down (- n 1)) (interaction-environment))))
      env)
(write (eval '(count-down 10000) env))
(newline)
