;; List procedures that need no Java (design.md D5). Included by (r7rs %prelude), after syntax.scm.

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

(define (memq x l) (if (pair? l) (if (eq? x (car l)) l (memq x (cdr l))) #f))
(define (memv x l) (if (pair? l) (if (eqv? x (car l)) l (memv x (cdr l))) #f))
(define (assq x l) (if (pair? l) (if (eq? x (car (car l))) (car l) (assq x (cdr l))) #f))
(define (assv x l) (if (pair? l) (if (eqv? x (car (car l))) (car l) (assv x (cdr l))) #f))

;; map and for-each call their procedure in a non-tail position, so they live here (D5). map
;; builds a fresh list each time, so re-entering a continuation never changes an earlier result.
(define (%cars ls) (if (pair? ls) (cons (car (car ls)) (%cars (cdr ls))) '()))
(define (%cdrs ls) (if (pair? ls) (cons (cdr (car ls)) (%cdrs (cdr ls))) '()))
(define (%all-pairs? ls) (if (pair? ls) (if (pair? (car ls)) (%all-pairs? (cdr ls)) #f) #t))

(define (map f l . ls)
  (if (null? ls)
      (let loop ((l l) (acc '()))
        (if (pair? l) (loop (cdr l) (cons (f (car l)) acc)) (reverse acc)))
      (let loop ((ls (cons l ls)) (acc '()))
        (if (%all-pairs? ls) (loop (%cdrs ls) (cons (apply f (%cars ls)) acc)) (reverse acc)))))

(define (for-each f l . ls)
  (if (null? ls)
      (let loop ((l l))
        (if (pair? l) (begin (f (car l)) (loop (cdr l)))))
      (let loop ((ls (cons l ls)))
        (if (%all-pairs? ls) (begin (apply f (%cars ls)) (loop (%cdrs ls)))))))
