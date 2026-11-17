; plan.md M9-03: a loop's jump back stores an argument in its parameter as soon as it is
; evaluated when no later argument reads that parameter; the others go through temporaries.
; Each loop here mixes the two, and must behave as if every argument were evaluated first.
(import (scheme base) (scheme write))

(define (show x) (write x) (newline))
; Calls each thunk in order (map's order is unspecified).
(define (call-all fs) (let loop ((fs fs) (out '())) (if (null? fs) (reverse out) (loop (cdr fs) (cons ((car fs)) out)))))

; No argument reads another's parameter: every store is direct.
(show (let loop ((i 0) (s 0)) (if (< i 10) (loop (+ i 1) (+ s 2)) (list i s))))

; A later argument reads an earlier parameter: that one waits in a temporary.
(show (let loop ((i 0) (acc '())) (if (< i 5) (loop (+ i 1) (cons i acc)) acc)))

; Swaps and rotations: every parameter is read by another argument.
(show (let loop ((a 1) (b 2) (k 0)) (if (= k 3) (list a b) (loop b a (+ k 1)))))
(show (let loop ((a 1) (b 2) (c 3) (k 0)) (if (= k 4) (list a b c) (loop b c a (+ k 1)))))

; An earlier argument reads a later parameter: both can be direct.
(show (let loop ((i 0) (j 10)) (if (< i 5) (loop (+ j 1) (- j 1)) (list i j))))

; A lambda in a later argument refers to an earlier parameter.
(show (let loop ((i 0) (fs '()))
        (if (< i 3) (loop (+ i 1) (cons (lambda () i) fs)) (call-all fs))))

; An assigned (boxed) parameter: each iteration's closures see their own box.
(show (let loop ((i 0) (n 0) (fs '()))
        (if (< i 3)
            (loop (+ i 1) (+ n 10) (cons (lambda () (set! n (+ n 1)) n) fs))
            (let* ((once (call-all fs)) (twice (call-all fs))) (list n once twice)))))

; A call's value as an argument (it goes through a temporary), with fixnums.
(define (tak x y z) (if (not (< y x)) z (tak (tak (- x 1) y z) (tak (- y 1) z x) (tak (- z 1) x y))))
(show (tak 18 12 6))

; A do loop, and a named let whose body makes non-tail calls.
(show (do ((i 0 (+ i 1)) (v (make-vector 5) v)) ((= i 5) v) (vector-set! v i (* i i))))
(define (sum-squares l) (let loop ((l l) (s 0)) (if (null? l) s (loop (cdr l) (+ s (square (car l)))))))
(show (sum-squares '(1 2 3 4)))

; A procedure's self tail call through a global, which is reassigned: the tail call that isn't
; a self call takes its arguments from where they were stored.
(define (f a b) (if (> a 0) (h (- a 1) (+ b 1)) (list 'f a b)))
(define h f)
(show (f 3 0))
(set! h (lambda (a b) (list 'h a b)))
(show (f 3 0))
(define (g a b) (if (> a 0) (g (- a 1) (cons a b)) b))
(show (g 4 '()))

; A continuation captured in a later argument and resumed: the earlier parameter was stored
; once, before the capture, and each resumption goes on from there. (One top-level form, so
; that resuming doesn't depend on how an implementation runs a program's later forms.)
(define (resumed)
  (let ((k #f) (seen '()) (results '()))
    (let ((r (let loop ((i 0) (j 0))
               (set! seen (cons (list i j) seen))
               (if (< i 3)
                   (loop (+ i 1) (call-with-current-continuation (lambda (c) (if (= i 1) (set! k c)) j)))
                   (list i j)))))
      (set! results (cons r results))
      (if (< (length seen) 8) (k 100))
      (list (reverse results) (reverse seen)))))
(show (resumed))

; The same with an assigned parameter: each resumption gets a fresh box.
(define (resumed-boxes)
  (let ((k #f) (boxes '()) (results '()))
    (let ((r (let loop ((i 0) (n 0))
               (set! boxes (cons (lambda () (set! n (+ n 1)) n) boxes))
               (if (< i 2)
                   (loop (+ i 1) (call-with-current-continuation (lambda (c) (if (= i 0) (set! k c)) (+ n 5))))
                   n))))
      (set! results (cons r results))
      (if (< (length boxes) 5) (k 50))
      (list (reverse results) (call-all (reverse boxes))))))
(show (resumed-boxes))
