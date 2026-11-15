;; limits: -Xss256k -Xmx64m
(import (scheme base) (scheme write))

(define (loop i acc)
  (if (= i 10000000)
      acc
      (loop (+ i 1) (+ acc 2))))

(display (loop 0 0))
(newline)
