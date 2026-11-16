(import (scheme base) (scheme write))

;; M1-06: a 10,000,000-iteration tail-recursive loop. `//tests:space` also runs this under a
;; small Java stack and heap to show it runs in constant space.
(define (loop i acc)
  (if (= i 10000000)
      acc
      (loop (+ i 1) (+ acc 1))))

(display (loop 0 0))
(newline)
