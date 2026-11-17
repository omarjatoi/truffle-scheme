(import (scheme base) (scheme write) (scheme file))

;; flush-output-port writes out what an open file port holds (R7RS 6.13.3), textual or binary, so
;; the file can be read back while the port is still open.
(define file "io-flush-diff.tmp")

(define (read-back-bytes)
  (call-with-port (open-binary-input-file file) (lambda (p) (read-bytevector 100000 p))))

(define (read-back-line)
  (call-with-port (open-input-file file) read-line))

;; chibi writes bytevector elements in hex, so they are shown as a list.
(define (bytes bv)
  (let loop ((i (- (bytevector-length bv) 1)) (l '()))
    (if (< i 0) l (loop (- i 1) (cons (bytevector-u8-ref bv i) l)))))

(define binary (open-binary-output-file file))
(write-u8 1 binary)
(write-bytevector #u8(2 3 4 5) binary 1 3)
(flush-output-port binary)
(write (bytes (read-back-bytes))) (newline)
(write-bytevector (make-bytevector 10000 9) binary)
(flush-output-port binary)
(write (bytevector-length (read-back-bytes))) (newline)
(close-port binary)

(define text (open-output-file file))
(write-string "flushed line" text)
(newline text)
(flush-output-port text)
(write (read-back-line)) (newline)
(close-port text)

(delete-file file)
(write (file-exists? file)) (newline)
