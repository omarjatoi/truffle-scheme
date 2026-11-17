;; The run that trains the launcher's AOT cache (design.md D17, "The AOT cache"): what every
;; program does as it starts (the launcher, a context, the standard libraries from the prelude
;; image, a program's expansion), and a little of what it does next.
(import (scheme base) (scheme write) (scheme process-context))

(define (count-up n)
  (let loop ((i 0) (acc '()))
    (if (= i n) (reverse acc) (loop (+ i 1) (cons i acc)))))

(write (apply + (count-up 100)))
(newline)
(display (string-append "arguments: " (number->string (length (command-line)))))
(newline)
