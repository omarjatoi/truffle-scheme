(import (scheme base) (scheme write))

;; Escape from inside non-tail recursion; the frames in between are abandoned.
(define (find-first pred l k)
  (if (null? l)
      #f
      (if (pred (car l))
          (k (car l))
          (+ 0 (find-first pred (cdr l) k)))))

(display (call/cc (lambda (k) (find-first (lambda (x) (< x 0)) '(1 2 -3 4) k))))
(newline)

;; The escaped value goes to the continuation of call/cc, not of the escape.
(display (+ 1 (call/cc (lambda (k) (+ 10 (k 5))))))
(newline)

(define (product l k)
  (if (null? l)
      1
      (if (= (car l) 0)
          (k 0)
          (* (car l) (product (cdr l) k)))))

(display (call/cc (lambda (k) (product '(1 2 3 0 5) k))))
(newline)
(display (call/cc (lambda (k) (product '(1 2 3 4 5) k))))
(newline)

;; An escape through procedures that called each other non-tail.
(define (f k) (* 2 (g k)))
(define (g k) (+ 1 (k 42)))
(display (list 'before (call/cc (lambda (k) (f k))) 'after))
(newline)
