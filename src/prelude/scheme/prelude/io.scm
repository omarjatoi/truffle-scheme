;; The input and output area (R7RS §6.13, AGENTS.md "Areas"): procedures written in Scheme (design.md
;; D5). The current ports are parameters; the port procedures default their port to them, then
;; call the area's %-primitives, which take the optional start and end themselves.

(define current-input-port (make-parameter (%stdin-port)))
(define current-output-port (make-parameter (%stdout-port)))
(define current-error-port (make-parameter (%stderr-port)))

(define (%input-port optional) (if (pair? optional) (car optional) (current-input-port)))
(define (%output-port optional) (if (pair? optional) (car optional) (current-output-port)))

;; (%with-range op obj rest): (op obj port [start [end]]) for an output procedure called as
;; (proc obj [port [start [end]]]).
(define (%with-range op obj rest)
  (if (and (pair? rest) (pair? (cdr rest)))
      (apply op obj rest)
      (op obj (%output-port rest))))

;; Input (R7RS §6.13.2).
(define (read . port) (%read (%input-port port)))
(define (read-char . port) (%read-char (%input-port port)))
(define (peek-char . port) (%peek-char (%input-port port)))
(define (read-line . port) (%read-line (%input-port port)))
(define (char-ready? . port) (%char-ready? (%input-port port)))
(define (read-string k . port) (%read-string k (%input-port port)))
(define (read-u8 . port) (%read-u8 (%input-port port)))
(define (peek-u8 . port) (%peek-u8 (%input-port port)))
(define (u8-ready? . port) (%u8-ready? (%input-port port)))
(define (read-bytevector k . port) (%read-bytevector k (%input-port port)))
;; (read-bytevector! bytevector [port [start [end]]])
(define (read-bytevector! bv . rest)
  (if (and (pair? rest) (pair? (cdr rest)))
      (apply %read-bytevector! bv rest)
      (%read-bytevector! bv (%input-port rest))))

;; Output (R7RS §6.13.3).
(define (display obj . port) (%display obj (%output-port port)))
(define (write obj . port) (%write obj (%output-port port)))
(define (write-shared obj . port) (%write-shared obj (%output-port port)))
(define (write-simple obj . port) (%write-simple obj (%output-port port)))
(define (newline . port) (%newline (%output-port port)))
(define (write-char char . port) (%write-char char (%output-port port)))
(define (write-u8 byte . port) (%write-u8 byte (%output-port port)))
(define (flush-output-port . port) (%flush-output-port (%output-port port)))
(define (write-string string . rest) (%with-range %write-string string rest))
(define (write-bytevector bv . rest) (%with-range %write-bytevector bv rest))

;; Procedures that call a procedure (R7RS §6.13.1, design.md D5): the port is closed when the
;; procedure returns, and the procedure's values are returned.
(define (call-with-port port proc)
  (call-with-values (lambda () (proc port))
    (lambda vals
      (close-port port)
      (apply values vals))))

(define (call-with-input-file file proc) (call-with-port (open-input-file file) proc))
(define (call-with-output-file file proc) (call-with-port (open-output-file file) proc))

(define (with-input-from-file file thunk)
  (call-with-port (open-input-file file)
    (lambda (port) (parameterize ((current-input-port port)) (thunk)))))
(define (with-output-to-file file thunk)
  (call-with-port (open-output-file file)
    (lambda (port) (parameterize ((current-output-port port)) (thunk)))))
