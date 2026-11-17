(import (scheme base) (scheme write))

;; call/cc whose continuation is used only in loops and local procedures that are themselves only
;; called (puzzle's, quicksort's and maze's shapes) compiles to an escape. Each case must behave
;; as with a captured continuation.

;; quicksort's check: a do loop that returns early.
(define (sorted? v)
  (call-with-current-continuation
   (lambda (return)
     (do ((i 1 (+ i 1)))
         ((= i (vector-length v)) #t)
       (unless (<= (vector-ref v (- i 1)) (vector-ref v i))
         (return #f))))))
(write (list (sorted? (vector 1 2 3)) (sorted? (vector 1 3 2))))
(newline)

;; puzzle's trial: a do loop that recurs into the procedure that holds it, and returns from it.
(define count 0)
(define (trial depth)
  (call-with-current-continuation
   (lambda (return)
     (do ((i 0 (+ i 1)))
         ((> i 3) (set! count (+ count 1)) #f)
       (cond ((or (= depth 4) (trial (+ depth 1)))
              (set! count (+ count 1))
              (return (+ depth i))))))))
(let ((result (trial 0)))
  (write (list result count)))
(newline)

;; maze's dig: a lambda handed to a procedure of the program whose loop only calls it.
(define (vector-for-each-rev proc v)
  (let lp ((i (- (vector-length v) 1)))
    (cond ((>= i 0)
           (proc (vector-ref v i))
           (lp (- i 1))))))
(define (dig v)
  (let ((seen '()))
    (call-with-current-continuation
     (lambda (quit)
       (vector-for-each-rev
        (lambda (x)
          (set! seen (cons x seen))
          (if (= x 0) (quit (list 'quit seen))))
        v)
       (list 'done seen)))))
(write (list (dig (vector 1 0 2 3)) (dig (vector 1 2))))
(newline)

;; Local helpers, mutually recursive, and a helper inside a helper.
(write
 (list
  (call/cc (lambda (k)
             (define (even? n) (if (= n 0) (k 'even) (odd? (- n 1))))
             (define (odd? n) (if (= n 0) (k 'odd) (even? (- n 1))))
             (+ 1 (even? 9))))
  (call/cc (lambda (k)
             (define (outer x) (define (inner y) (if (> y 2) (k y) (+ 10 (inner (+ y 1))))) (* 2 (inner x)))
             (outer 0)))
  (call/cc (lambda (k)
             (let ((h (lambda (x) (k (* x x)))))
               (+ 1 (h 7)))))))
(newline)

;; A full continuation captured inside the loop, re-entered after call/cc has returned: the loop's
;; k still returns from its own (copied) call/cc.
(define (reenter)
  (let ((saved #f)
        (results '()))
    (let ((v (call/cc (lambda (k)
                        (do ((i 0 (+ i 1))) (#f)
                          (when (= i 3)
                            (k (+ (call/cc (lambda (c) (set! saved c) 0)) i))))))))
      (set! results (cons v results))
      (if (< (length results) 3) (saved (* 10 (length results))))
      (reverse results))))
(write (reenter))
(newline)
