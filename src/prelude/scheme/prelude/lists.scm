;; List procedures that need no Java (design.md D5). Core forms only. Included by (r7rs %prelude).

(define (list . elements) elements)

(define (caar x) (car (car x)))
(define (cadr x) (car (cdr x)))
(define (cdar x) (cdr (car x)))
(define (cddr x) (cdr (cdr x)))
(define (caddr x) (car (cdr (cdr x))))
(define (cdddr x) (cdr (cdr (cdr x))))

(define (length l)
  (define (loop rest n)
    (if (null? rest)
        n
        (if (pair? rest)
            (loop (cdr rest) (+ n 1))
            (%error "length: not a proper list" l))))
  (loop l 0))

(define (reverse l)
  (define (loop rest acc)
    (if (null? rest)
        acc
        (if (pair? rest)
            (loop (cdr rest) (cons (car rest) acc))
            (%error "reverse: not a proper list" l))))
  (loop l '()))
