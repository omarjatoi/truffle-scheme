(import (scheme base) (scheme write))

;; M5-04: call/cc whose continuation the compiler proves escape-only (only called, or passed to
;; procedures of this program that only call it) compiles to an escape. Each case must behave as
;; with a captured continuation.

(define (ctak x y z) (call/cc (lambda (k) (ctak-aux k x y z))))
(define (ctak-aux k x y z)
  (if (not (< y x))
      (k z)
      (call/cc
       (lambda (k)
         (ctak-aux k
                   (call/cc (lambda (k) (ctak-aux k (- x 1) y z)))
                   (call/cc (lambda (k) (ctak-aux k (- y 1) z x)))
                   (call/cc (lambda (k) (ctak-aux k (- z 1) x y))))))))
(write (ctak 18 12 6))
(newline)

(define (addc x y k) (if (zero? y) (k x) (addc (+ x 1) (- y 1) k)))
(define (fibc x c)
  (if (zero? x)
      (c 0)
      (if (zero? (- x 1))
          (c 1)
          (addc (call/cc (lambda (c) (fibc (- x 1) c))) (call/cc (lambda (c) (fibc (- x 2) c))) c))))
(write (fibc 15 (lambda (n) n)))
(newline)

;; Values, through non-tail frames.
(define (two k) (* 2 (k 3 4)))
(write (list (call-with-values (lambda () (call/cc (lambda (k) (k 1 2)))) list)
             (call-with-values (lambda () (call/cc (lambda (k) (k)))) list)
             (call-with-values (lambda () (call/cc (lambda (k) (+ 1 (two k))))) list)))
(newline)

;; dynamic-wind around the call/cc; handlers and guard around and inside it.
(define trail '())
(define (note x) (set! trail (cons x trail)))
(define (leave k) (+ 1 (k 'left)))
(note (dynamic-wind (lambda () (note 'in)) (lambda () (call/cc (lambda (k) (leave k)))) (lambda () (note 'out))))
(write (reverse trail))
(newline)

(define (g k) (k (list 'escaped (+ 1 (raise-continuable 'oops)))))
(define (r k) (raise 'boom) (k 'not-reached))
(write (list (with-exception-handler
              (lambda (e) 10)
              (lambda () (guard (e ((string? e) 'string)) (call/cc (lambda (k) (g k))))))
             (guard (e ((eq? e 'boom) 'caught)) (call/cc (lambda (k) (r k))))
             (with-exception-handler
              (lambda (e) 'handled)
              (lambda () (call/cc (lambda (k) (leave k))) (raise-continuable 'x)))))
(newline)

;; A continuation captured inside the escape's extent, re-entered after it returned.
(define (reenter)
  (define saved #f)
  (define n 0)
  (define results '())
  (define (f k) (+ 100 (h k)))
  (define (h k)
    (call/cc (lambda (c) (set! saved c)))
    (set! n (+ n 1))
    (+ 1000 (k (+ 10 n))))
  (define v (call/cc (lambda (k) (f k))))
  (set! results (cons v results))
  (if (< n 3) (saved #f))
  (reverse results))
(write (reenter))
(newline)

;; Deep non-tail recursion between call/cc and the escape.
(define (down i k) (if (= i 5000) (k (list 'escaped i)) (+ 1 (down (+ i 1) k))))
(write (call/cc (lambda (k) (down 0 k))))
(newline)

;; call/cc in tail position, a hundred thousand times.
(define (loop i k) (if (< i 100000) (call/cc (lambda (j) (loop (+ i 1) j))) (k i)))
(write (call/cc (lambda (k) (loop 0 k))))
(newline)
