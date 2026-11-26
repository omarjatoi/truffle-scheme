; A recursion deep enough to spill its outer frames to the heap.
; options: --experimental-options --scheme.SpillDepth=5
(import (scheme base) (scheme eval) (scheme load) (scheme process-context))
(define (down n)
  (if (= n 0)
      (list RAISE)
      (+ 1 (down (- n 1)))))
(down 30)
