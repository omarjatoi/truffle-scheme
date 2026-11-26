(import (scheme base) (scheme write))

;; A string port written to until its buffer can't grow (design.md D8, "Running out of memory");
;; //tests:memory runs it under a 64 MB heap.
(display "start")
(newline)
(define port (open-output-string))
(let loop ()
  (write-string "0123456789abcdef" port)
  (loop))
