;; limits: -Xss256k -Xmx64m
(import (scheme base) (scheme case-lambda) (scheme write))

;; R7RS §3.5: the tail positions of the derived expression types.
(define n 1000000)

(define (t-cond i) (cond ((>= i n) 'cond) ((= i -1) 'never) (else (t-cond (+ i 1)))))
(define (t-cond=> i) (cond ((>= i n) 'cond=>) ((+ i 1) => t-cond=>)))
(define (t-case i) (case (if (>= i n) 'stop 'go) ((stop) 'case) ((go) (t-case (+ i 1)))))
(define (t-case=> i) (case (if (>= i n) 'stop 'go) ((stop) 'case=>) (else => (lambda (x) (t-case=> (+ i 1))))))
(define (t-and i) (if (>= i n) 'and (and #t (t-and (+ i 1)))))
(define (t-or i) (if (>= i n) 'or (or #f (t-or (+ i 1)))))
(define (t-when i) (if (>= i n) 'when (when #t (t-when (+ i 1)))))
(define (t-unless i) (if (>= i n) 'unless (unless #f (t-unless (+ i 1)))))
(define (t-let i) (if (>= i n) 'let (let ((j (+ i 1))) (t-let j))))
(define (t-let* i) (if (>= i n) 'let* (let* ((j (+ i 1)) (k j)) (t-let* k))))
(define (t-letrec i) (if (>= i n) 'letrec (letrec ((j (+ i 1))) (t-letrec j))))
(define (t-letrec* i) (if (>= i n) 'letrec* (letrec* ((j (+ i 1))) (t-letrec* j))))
(define (t-let-values i) (if (>= i n) 'let-values (let-values (((j k) (values (+ i 1) 0))) (t-let-values j))))
(define (t-let*-values i) (if (>= i n) 'let*-values (let*-values (((j) (values (+ i 1)))) (t-let*-values j))))
(define t-case-lambda
  (case-lambda
    ((i) (t-case-lambda i 'x))
    ((i x) (if (>= i n) 'case-lambda (t-case-lambda (+ i 1))))))

;; Named let and do loop in constant space too.
(define (t-named-let) (let loop ((i 0)) (if (>= i n) 'named-let (loop (+ i 1)))))
(define (t-do) (do ((i 0 (+ i 1))) ((>= i n) 'do)))
;; A named let whose loop is called from a non-self position.
(define (t-named-let-escape) (let loop ((i 0)) (if (>= i n) 'named-let-arg ((lambda (f) (f (+ i 1))) loop))))

(for-each (lambda (thunk) (display (thunk)) (newline))
          (list (lambda () (t-cond 0)) (lambda () (t-cond=> 0)) (lambda () (t-case 0)) (lambda () (t-case=> 0))
                (lambda () (t-and 0)) (lambda () (t-or 0)) (lambda () (t-when 0)) (lambda () (t-unless 0))
                (lambda () (t-let 0)) (lambda () (t-let* 0)) (lambda () (t-letrec 0)) (lambda () (t-letrec* 0))
                (lambda () (t-let-values 0)) (lambda () (t-let*-values 0)) (lambda () (t-case-lambda 0))
                t-named-let t-do t-named-let-escape))
