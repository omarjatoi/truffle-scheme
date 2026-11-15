;; limits: -Xss256k -Xmx512m
(import (scheme base) (scheme write))

(define (show . xs) (display xs))

;; guard catches a raise from deep inside dynamic-winds; the after-thunks run first.
(define (deep i)
  (if (= i 100000)
      (dynamic-wind (lambda () (show 'in)) (lambda () (raise 'boom)) (lambda () (show 'out)))
      (+ 1 (deep (+ i 1)))))
(display (guard (e ((symbol? e) (list 'caught e))) (deep 0)))
(newline)

;; No clause matches: the inner guard re-raises with raise-continuable in the dynamic environment
;; of the original raise (R7RS §4.2.7), so the before-thunk runs again.
(display (guard (e (#t (list 'outer e)))
  (guard (e ((string? e) 'inner))
    (dynamic-wind (lambda () (show 'in)) (lambda () (raise 42)) (lambda () (show 'out))))))
(newline)

;; raise-continuable: the handler's value returns to the raise.
(display (with-exception-handler
          (lambda (e) (* e 2))
          (lambda () (+ 1 (raise-continuable 20)))))
(newline)

;; A guard body re-entered through a continuation after the guard has returned: the guard is
;; live again, so a raise inside it is caught there.
(define k #f)
(define count 0)
(display (guard (e (#t (list 'caught e)))
  (+ 1 (let ((v (call/cc (lambda (c) (set! k c) 0))))
         (if (= v 99) (raise 'late) v)))))
(newline)
(set! count (+ count 1))
(if (< count 2) (k 99))
