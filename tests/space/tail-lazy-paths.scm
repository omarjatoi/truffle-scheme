(import (scheme base) (scheme write))

;; R7RS §3.5: a loop whose first iteration takes a path the others never take again runs in
;; constant space. The let on that path holds the head; the loop's jump back to its start is a
;; tail call of the let's body, so the variable must not stay set for the iterations after it. A
;; self tail loop, and a named let. (The other variables put the head's local past the ones the
;; other path uses, which would otherwise overwrite it.)
(define (from n) (vector #f n))

(define (next s)
  (if (vector-ref s 0)
      (vector-ref s 1)
      (let ((v (cons (vector-ref s 1) (vector #f (+ 1 (vector-ref s 1))))))
        (vector-set! s 0 #t)
        (vector-set! s 1 v)
        v)))

(define (walk s k first?)
  (cond ((= k 0) (car (next s)))
        (first? (let ((a 1) (b 2) (c 3) (d 4) (e 5) (f 6) (head s))
                  (walk (cdr (next head)) (- k (- (+ a b c d f) e 10)) #f)))
        (else (walk (cdr (next s)) (- k 1) #f))))

(define (run n)
  (let loop ((s (from 0)) (k n) (first? #t))
    (cond ((= k 0) (car (next s)))
          (first? (let ((a 1) (b 2) (c 3) (d 4) (e 5) (f 6) (head s))
                    (loop (cdr (next head)) (- k (- (+ a b c d f) e 10)) #f)))
          (else (loop (cdr (next s)) (- k 1) #f)))))

(let ((a (walk (from 0) 3000000 #t))
      (b (run 3000000)))
  (display (if (= a b) a (list a b))))
(newline)
