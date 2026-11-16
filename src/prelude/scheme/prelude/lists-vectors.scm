;; The lists and vectors area (AGENTS.md "Areas"): list and vector procedures written in Scheme
;; (design.md D5).

(define (list . elements) elements)

(define (caar x) (car (car x)))
(define (cadr x) (car (cdr x)))
(define (cdar x) (cdr (car x)))
(define (cddr x) (cdr (cdr x)))
(define (caddr x) (car (cdr (cdr x))))
(define (cdddr x) (cdr (cdr (cdr x))))

;; length, reverse, memq, memv, assq and assv are primitives (ListVectorPrimitives.java), which
;; raise on a circular list instead of walking it forever (design.md D9).

;; map and for-each call their procedure in a non-tail position, so they live here (D5). map
;; builds a fresh list each time, so re-entering a continuation never changes an earlier result.
(define (%cars ls) (if (pair? ls) (cons (car (car ls)) (%cars (cdr ls))) '()))
(define (%cdrs ls) (if (pair? ls) (cons (cdr (car ls)) (%cdrs (cdr ls))) '()))
(define (%all-pairs? ls) (if (pair? ls) (if (pair? (car ls)) (%all-pairs? (cdr ls)) #f) #t))

;; map and for-each stop at the shortest list, so their lists may be circular, but "it is an error
;; if all of them are" (R7RS §6.10): we raise instead of looping forever (design.md D9).
(define (%some-finite message l ls)
  (if (%circular-list? l)
      (let loop ((rest ls))
        (if (pair? rest)
            (if (%circular-list? (car rest)) (loop (cdr rest)))
            (apply error message l ls)))))

(define (map f l . ls)
  (if (null? ls)
      (begin
        (%some-finite "map: every list is circular" l '())
        (let loop ((l l) (acc '()))
          (if (pair? l) (loop (cdr l) (cons (f (car l)) acc)) (reverse acc))))
      (begin
        (%some-finite "map: every list is circular" l ls)
        (let loop ((ls (cons l ls)) (acc '()))
          (if (%all-pairs? ls) (loop (%cdrs ls) (cons (apply f (%cars ls)) acc)) (reverse acc))))))

(define (for-each f l . ls)
  (if (null? ls)
      (begin
        (%some-finite "for-each: every list is circular" l '())
        (let loop ((l l))
          (if (pair? l) (begin (f (car l)) (loop (cdr l))))))
      (begin
        (%some-finite "for-each: every list is circular" l ls)
        (let loop ((ls (cons l ls)))
          (if (%all-pairs? ls) (begin (apply f (%cars ls)) (loop (%cdrs ls))))))))

;; member and assoc take an optional equivalence predicate, which they call in a non-tail position,
;; so they live here (D5). The predicate gets the key first and the element second (R7RS §6.4).
;; Without one, they are the primitives %member and %assoc (equal?). With one, the walk checks for a
;; cycle as it goes, as the primitives do (design.md D9), with Brent's method: it keeps a pair, moved
;; up to the walk after 1, 2, 4, ... steps, which the walk meets again only on a cycle.
(define (member x l . compare)
  (if (pair? compare)
      (let ((same? (car compare)))
        (let loop ((rest l) (mark l) (n 0) (limit 1))
          (if (pair? rest)
              (if (same? x (car rest))
                  rest
                  (let ((next (cdr rest)))
                    (cond ((eq? next mark) (error "member: not a proper list" l))
                          ((= n limit) (loop next next 0 (+ limit limit)))
                          (else (loop next mark (+ n 1) limit)))))
              #f)))
      (%member x l)))

(define (assoc x l . compare)
  (if (pair? compare)
      (let ((same? (car compare)))
        (let loop ((rest l) (mark l) (n 0) (limit 1))
          (if (pair? rest)
              (if (same? x (car (car rest)))
                  (car rest)
                  (let ((next (cdr rest)))
                    (cond ((eq? next mark) (error "assoc: not a proper list" l))
                          ((= n limit) (loop next next 0 (+ limit limit)))
                          (else (loop next mark (+ n 1) limit)))))
              #f)))
      (%assoc x l)))

;; vector-map and vector-for-each call their procedure in a non-tail position (D5). They stop at
;; the shortest vector. vector-map collects its results in a fresh list and only then builds the
;; vector, so re-entering a continuation never changes an earlier result.
(define (%min-vector-length vs n)
  (if (pair? vs) (%min-vector-length (cdr vs) (let ((m (vector-length (car vs)))) (if (< m n) m n))) n))
(define (%vector-refs vs i)
  (if (pair? vs) (cons (vector-ref (car vs) i) (%vector-refs (cdr vs) i)) '()))

(define (vector-map f v . vs)
  (if (null? vs)
      (let ((n (vector-length v)))
        (let loop ((i 0) (acc '()))
          (if (< i n)
              (loop (+ i 1) (cons (f (vector-ref v i)) acc))
              (list->vector (reverse acc)))))
      (let* ((vs (cons v vs)) (n (%min-vector-length vs (vector-length v))))
        (let loop ((i 0) (acc '()))
          (if (< i n)
              (loop (+ i 1) (cons (apply f (%vector-refs vs i)) acc))
              (list->vector (reverse acc)))))))

(define (vector-for-each f v . vs)
  (if (null? vs)
      (let ((n (vector-length v)))
        (let loop ((i 0))
          (if (< i n) (begin (f (vector-ref v i)) (loop (+ i 1))))))
      (let* ((vs (cons v vs)) (n (%min-vector-length vs (vector-length v))))
        (let loop ((i 0))
          (if (< i n) (begin (apply f (%vector-refs vs i)) (loop (+ i 1))))))))

;; The rest of (scheme cxr).
(define (caaar x) (car (car (car x))))
(define (caadr x) (car (car (cdr x))))
(define (cadar x) (car (cdr (car x))))
(define (cdaar x) (cdr (car (car x))))
(define (cdadr x) (cdr (car (cdr x))))
(define (cddar x) (cdr (cdr (car x))))
(define (caaaar x) (car (car (car (car x)))))
(define (caaadr x) (car (car (car (cdr x)))))
(define (caadar x) (car (car (cdr (car x)))))
(define (caaddr x) (car (car (cdr (cdr x)))))
(define (cadaar x) (car (cdr (car (car x)))))
(define (cadadr x) (car (cdr (car (cdr x)))))
(define (caddar x) (car (cdr (cdr (car x)))))
(define (cadddr x) (car (cdr (cdr (cdr x)))))
(define (cdaaar x) (cdr (car (car (car x)))))
(define (cdaadr x) (cdr (car (car (cdr x)))))
(define (cdadar x) (cdr (car (cdr (car x)))))
(define (cdaddr x) (cdr (car (cdr (cdr x)))))
(define (cddaar x) (cdr (cdr (car (car x)))))
(define (cddadr x) (cdr (cdr (car (cdr x)))))
(define (cdddar x) (cdr (cdr (cdr (car x)))))
(define (cddddr x) (cdr (cdr (cdr (cdr x)))))
