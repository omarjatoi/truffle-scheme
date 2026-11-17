(import (scheme base) (scheme write) (scheme file))

;; Binary file input (R7RS 6.13.2): read-u8, peek-u8, u8-ready?, read-bytevector and
;; read-bytevector! mixed, over a file larger than a port's buffer, up to and past its end.
(define file "io-binary-input-diff.tmp")
(define size 20000)

;; The file's byte i: a pattern that doesn't repeat every 8 KB.
(define (expected i) (modulo (+ (* i 7) (quotient i 251)) 256))

(call-with-port (open-binary-output-file file)
  (lambda (p)
    (do ((i 0 (+ i 1))) ((= i size)) (write-u8 (expected i) p))))

(define port (open-binary-input-file file))
(define pos 0)
(define wrong 0)

(define (check-byte b)
  (unless (eqv? b (expected pos)) (set! wrong (+ wrong 1)))
  (set! pos (+ pos 1)))

(define (check-bytes bv start end)
  (do ((i start (+ i 1))) ((= i end)) (check-byte (bytevector-u8-ref bv i))))

;; chibi writes bytevector elements in hex, so lengths and positions are shown instead.
(let ((bv (read-bytevector 8190 port)))
  (check-bytes bv 0 (bytevector-length bv))
  (write (list 'read-bytevector (bytevector-length bv) pos)) (newline))

(write (list 'u8-ready? (u8-ready? port))) (newline)

;; Peeks and reads over 8 KB.
(do () ((= pos 8195))
  (let* ((a (peek-u8 port)) (b (peek-u8 port)) (c (read-u8 port)))
    (unless (and (eqv? a b) (eqv? b c)) (set! wrong (+ wrong 1)))
    (check-byte c)))
(write (list 'read-u8 pos)) (newline)

;; Into the middle of a bytevector, over 16 KB.
(let* ((bv (make-bytevector 8500 0)) (n (read-bytevector! bv port 100 8300)))
  (check-bytes bv 100 8300)
  (write (list 'read-bytevector! n pos (bytevector-u8-ref bv 99) (bytevector-u8-ref bv 8300)))
  (newline))

;; Byte by byte, then more than is left.
(do ((i 0 (+ i 1))) ((= i 1000)) (check-byte (read-u8 port)))
(write (list 'peek (eqv? (peek-u8 port) (expected pos)))) (newline)
(let ((bv (read-bytevector 10000 port)))
  (check-bytes bv 0 (bytevector-length bv))
  (write (list 'rest (bytevector-length bv) pos)) (newline))

;; The end of the file, every way but u8-ready?, which R7RS says is #t there and chibi-scheme
;; answers #f (IoTest checks ours).
(write (list 'eof
             (eof-object? (peek-u8 port))
             (eof-object? (read-u8 port))
             (eof-object? (read-u8 port))
             (eof-object? (read-bytevector 10 port))
             (eof-object? (read-bytevector! (make-bytevector 4) port))))
(newline)
(close-port port)

(write (list 'wrong wrong)) (newline)
(delete-file file)
