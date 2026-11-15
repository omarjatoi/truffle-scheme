;; The input and output area (R7RS §6.13, AGENTS.md "Areas"): procedures written in Scheme (design.md
;; D5). current-output-port is a parameter; output procedures default their port to it.

(define current-output-port (make-parameter (%stdout-port)))

(define (%output-port optional) (if (pair? optional) (car optional) (current-output-port)))

(define (display obj . port) (%display obj (%output-port port)))
(define (write obj . port) (%write obj (%output-port port)))
(define (write-shared obj . port) (%write-shared obj (%output-port port)))
(define (write-simple obj . port) (%write-simple obj (%output-port port)))
(define (newline . port) (%write-char #\newline (%output-port port)))
(define (write-char char . port) (%write-char char (%output-port port)))
(define (flush-output-port . port) (%flush-output-port (%output-port port)))

;; (write-string string [port [start [end]]])
(define (write-string string . rest)
  (%write-string (if (and (pair? rest) (pair? (cdr rest)))
                     (apply substring string (cdr rest))
                     string)
                 (%output-port rest)))
