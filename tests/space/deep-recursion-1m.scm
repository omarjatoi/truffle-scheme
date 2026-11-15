(import (scheme base) (scheme write))

;; M2-05: 1,000,000-deep non-tail recursion (the M1-06 control). `mx tests space` runs this under
;; -Xss256k: stack-depth spill keeps the Java stack constant. Not a tests/diff/ program: chibi-scheme
;; runs out of stack space on it.
(define (count i)
  (if (= i 1000000)
      0
      (+ 1 (count (+ i 1)))))

(display (count 0))
(newline)
