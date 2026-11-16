;; Records (R7RS §5.5) on the Static Object Model (plan.md M7-04): semantics, and a record-heavy
;; workload (a binary search tree and a queue of records) that benchmark-style timing uses.
(import (scheme base) (scheme write))

(define (show x) (write x) (newline))

(define-record-type point (make-point x y) point? (x point-x set-point-x!) (y point-y))

;; Constructor, predicate, accessors, modifier; records are not vectors, pairs or procedures.
(define p (make-point 1 2))
(show (list (point? p) (point-x p) (point-y p)))
(set-point-x! p 10)
(show (point-x p))
(show (map point? (list 1 '(1 2) (vector 1 2) "point" point-x p)))
(show (list (vector? p) (pair? p) (procedure? p) (procedure? point-x) (procedure? make-point)))

;; Records compare by identity. (Not equal? on two records with equal fields: R7RS §6.1 says eqv?,
;; and chibi compares their fields.)
(define q (make-point 10 2))
(show (list (eq? p p) (eqv? p q) (eqv? p p) (equal? p p)))

;; A constructor that sets some fields, in another order; a field with no constructor argument.
(define-record-type triple (make-triple c a) triple? (a triple-a) (b triple-b set-triple-b!) (c triple-c))
(define t (make-triple 3 1))
(set-triple-b! t 2)
(show (list (triple-a t) (triple-b t) (triple-c t)))

;; Zero fields, and many.
(define-record-type unit (make-unit) unit?)
(show (list (unit? (make-unit)) (eq? (make-unit) (make-unit)) (point? (make-unit))))
(define-record-type wide (make-wide f0 f1 f2 f3 f4 f5 f6 f7 f8 f9) wide?
  (f0 wide-f0) (f1 wide-f1) (f2 wide-f2) (f3 wide-f3) (f4 wide-f4)
  (f5 wide-f5) (f6 wide-f6) (f7 wide-f7) (f8 wide-f8) (f9 wide-f9 set-wide-f9!))
(define w (make-wide 0 1 2 3 4 5 6 7 8 9))
(set-wide-f9! w 'nine)
(show (list (wide-f0 w) (wide-f5 w) (wide-f9 w)))

;; Types with the same fields are distinct: an accessor of one rejects the other's records.
(define-record-type point2 (make-point2 x y) point2? (x point2-x) (y point2-y))
(show (list (point? (make-point2 1 2)) (point2? p)))
(show (guard (e ((error-object? e) 'wrong-type)) (point-x (make-point2 1 2))))
(show (guard (e ((error-object? e) 'wrong-type)) (point-x 5)))
(show (guard (e ((error-object? e) 'wrong-type)) (set-point-x! (make-point2 1 2) 0)))

;; Evaluating one definition twice makes two distinct types.
(define (make-type)
  (define-record-type cell (make-cell v) cell? (v cell-v))
  (list make-cell cell? cell-v))
(define t1 (make-type))
(define t2 (make-type))
(define c1 ((car t1) 'one))
(define c2 ((car t2) 'two))
(show (list ((cadr t1) c1) ((cadr t1) c2) ((cadr t2) c2) ((cadr t2) c1)))
(show (list ((list-ref t1 2) c1) ((list-ref t2 2) c2)))
(show (guard (e ((error-object? e) 'wrong-type)) ((list-ref t1 2) c2)))

;; Accessors and modifiers as values: through apply, map and in a variable.
(show (map point-x (list (make-point 1 0) (make-point 2 0) (make-point 3 0))))
(show (point? (apply make-point '(7 8))))
(show (apply point-y (list (make-point 7 8))))
(let ((get point-x) (put! set-point-x!))
  (put! p 42)
  (show (get p)))

;; A record mutated after a continuation was captured: re-entering sees the record as it is
;; (records are shared state, not copied with frames).
(show (let* ((k #f)
             (r (make-point 0 0))
             (visits (call/cc (lambda (c) (set! k c) 0))))
        (set-point-x! r (+ (point-x r) 1))
        (if (< visits 3)
            (k (+ visits 1))
            (list visits (point-x r)))))

;; The workload: a binary search tree of records, built by mutation and walked by accessors.
(define-record-type node (make-node key left right) node?
  (key node-key) (left node-left set-node-left!) (right node-right set-node-right!))

(define (insert! tree key)
  (let loop ((n tree))
    (cond ((< key (node-key n))
           (if (node? (node-left n))
               (loop (node-left n))
               (set-node-left! n (make-node key #f #f))))
          ((> key (node-key n))
           (if (node? (node-right n))
               (loop (node-right n))
               (set-node-right! n (make-node key #f #f)))))))

(define (tree-sum n)
  (if (node? n) (+ (node-key n) (tree-sum (node-left n)) (tree-sum (node-right n))) 0))

(define (tree-depth n)
  (if (node? n) (+ 1 (max (tree-depth (node-left n)) (tree-depth (node-right n)))) 0))

(define (random-keys count)
  (let loop ((i 0) (seed 12345) (keys '()))
    (if (= i count)
        keys
        (let ((next (modulo (+ (* seed 1103515245) 12345) 2147483648)))
          (loop (+ i 1) next (cons (quotient next 65536) keys))))))

(define (build keys)
  (let ((tree (make-node (car keys) #f #f)))
    (for-each (lambda (k) (insert! tree k)) (cdr keys))
    tree))

;; A queue of records: two-field cells linked through a mutable field.
(define-record-type qcell (make-qcell value next) qcell? (value qcell-value) (next qcell-next set-qcell-next!))
(define-record-type queue (%make-queue head tail) queue? (head queue-head set-queue-head!) (tail queue-tail set-queue-tail!))
(define (make-queue) (%make-queue #f #f))
(define (enqueue! q v)
  (let ((c (make-qcell v #f)))
    (if (qcell? (queue-tail q)) (set-qcell-next! (queue-tail q) c) (set-queue-head! q c))
    (set-queue-tail! q c)))
(define (dequeue! q)
  (let ((c (queue-head q)))
    (set-queue-head! q (qcell-next c))
    (if (not (qcell? (queue-head q))) (set-queue-tail! q #f))
    (qcell-value c)))

;; Breadth-first: the sum of keys at each depth.
(define (level-sums tree)
  (let ((q (make-queue)))
    (enqueue! q (cons tree 0))
    (let loop ((sums '()))
      (if (not (qcell? (queue-head q)))
          sums
          (let* ((entry (dequeue! q)) (n (car entry)) (d (cdr entry)))
            (if (node? (node-left n)) (enqueue! q (cons (node-left n) (+ d 1))))
            (if (node? (node-right n)) (enqueue! q (cons (node-right n) (+ d 1))))
            (loop (let add ((s sums) (i 0))
                    (cond ((null? s) (list (node-key n)))
                          ((= i d) (cons (+ (car s) (node-key n)) (cdr s)))
                          (else (cons (car s) (add (cdr s) (+ i 1))))))))))))

(define (run rounds size)
  (let loop ((i 0) (acc 0))
    (if (= i rounds)
        acc
        (let ((tree (build (random-keys size))))
          (loop (+ i 1) (+ acc (tree-sum tree) (apply + (level-sums tree)) (tree-depth tree)))))))

(show (run 20 2000))
