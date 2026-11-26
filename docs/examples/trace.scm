(import (scheme base))

(define (check items)
  (map (lambda (item) (+ 1 (car item))) items))

(define (down n)
  (if (= n 0)
      (list (check '((1) 2)))
      (+ 1 (down (- n 1)))))

(down 3)
