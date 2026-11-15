;; limits: -Xss256k -Xmx256m
(import (scheme base) (scheme write))

;; write and display of a list nested 100,000 deep: the printer must not recurse.
(define (nest i x) (if (= i 0) x (nest (- i 1) (list x))))
(define deep (nest 100000 'leaf))
(write deep)
(newline)
(display deep)
(newline)
