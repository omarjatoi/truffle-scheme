(import (scheme base) (scheme write))

;; R7RS §6.10.
(define (example)
  (define path '())
  (define c #f)
  (define (add s) (set! path (cons s path)))
  (dynamic-wind
    (lambda () (add 'connect))
    (lambda () (add (call-with-current-continuation (lambda (c0) (set! c c0) 'talk1))))
    (lambda () (add 'disconnect)))
  (if (< (length path) 4)
      (c 'talk2)
      (reverse path)))
(display (example))
(newline)

(define (show . xs) (display xs))

;; An escape runs the after-thunks, innermost first.
(display (call/cc (lambda (k)
  (dynamic-wind (lambda () (show 'a-in)) (lambda ()
    (dynamic-wind (lambda () (show 'b-in)) (lambda () (k 'escaped)) (lambda () (show 'b-out))))
    (lambda () (show 'a-out))))))
(newline)

;; Re-entry runs the before-thunks, outermost first.
(define (reenter)
  (define k #f)
  (define n 0)
  (dynamic-wind (lambda () (show 'a-in)) (lambda ()
    (dynamic-wind (lambda () (show 'b-in)) (lambda () (call/cc (lambda (c) (set! k c))))
                  (lambda () (show 'b-out))))
    (lambda () (show 'a-out)))
  (display "|")
  (set! n (+ n 1))
  (if (< n 2) (k 'again) 'done))
(display (reenter))
(newline)

;; Jumping from inside one dynamic-wind into another: only the parts not shared run.
(define (sideways)
  (define into #f)
  (define count 0)
  (dynamic-wind (lambda () (show 'outer-in)) (lambda ()
    (dynamic-wind (lambda () (show 'x-in)) (lambda () (call/cc (lambda (k) (set! into k)))) (lambda () (show 'x-out)))
    (dynamic-wind (lambda () (show 'y-in))
                  (lambda () (set! count (+ count 1)) (if (= count 1) (into 'back)))
                  (lambda () (show 'y-out))))
    (lambda () (show 'outer-out))))
(sideways)
(newline)
