(import (scheme base) (scheme write))
;; M6-04: down recurses 500 deep, ten times the spill depth (--scheme.SpillDepth=50, tools.py), so
;; most of its frames are on the heap while spin runs at the bottom. Samples there must have all 500.
(define (spin i) (if (= i 0) 0 (spin (- i 1))))
(define (down n)
  (if (= n 0)
      (spin 100000000)
      (+ 1 (down (- n 1)))))
(display (down 500))
(newline)
