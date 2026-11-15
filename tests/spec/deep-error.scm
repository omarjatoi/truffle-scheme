;; limits: -Xss256k -Xmx512m
;; exit: 70
(import (scheme base) (scheme write))

;; An uncaught error 1,000,000 frames deep ends the program with status 70 (design.md D8).
(display "start")
(newline)
(define (down i) (if (= i 1000000) (car '()) (+ 1 (down (+ i 1)))))
(down 0)
(display "not reached")
(newline)
