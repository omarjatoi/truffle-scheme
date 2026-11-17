(import (scheme base) (scheme write) (scheme file) (scheme char))

;; Textual file input (R7RS 6.13.2): read-string in chunks of many sizes, mixed with read-char,
;; peek-char and read-line, over a file larger than a port's buffer whose characters take one to
;; four bytes in UTF-8 (and a surrogate pair in UTF-16), up to and past its end.
(define file "io-read-string-diff.tmp")
(define size 30000)

;; The file's character i: a pattern that doesn't repeat every 8 KB.
(define chars (vector #\a #\b #\x00E9 #\x03BB #\x1F600 #\space #\z #\x4E2D #\newline))
(define (expected i) (vector-ref chars (modulo (+ i (quotient i 251)) (vector-length chars))))

(call-with-output-file file
  (lambda (p)
    (do ((i 0 (+ i 1))) ((= i size)) (write-char (expected i) p))))

(define port (open-input-file file))
(define pos 0)
(define wrong 0)

(define (check-char c)
  (unless (eqv? c (expected pos)) (set! wrong (+ wrong 1)))
  (set! pos (+ pos 1)))

(define (check-string s)
  (string-for-each check-char s)
  (string-length s))

;; read-string in chunks of each size, a peek-char and a read-char between them.
(for-each
 (lambda (k)
   (let ((n (check-string (read-string k port))))
     (unless (eqv? (peek-char port) (expected pos)) (set! wrong (+ wrong 1)))
     (check-char (read-char port))
     (write (list 'read-string k n pos)) (newline)))
 '(1 2 3 5 7 16 100 1000 4095 4096 4097 8192))

(write (list 'empty (read-string 0 port) pos)) (newline)

;; A line, then a peek before a read-string.
(let ((line (read-line port)))
  (check-string line)
  (check-char #\newline)
  (write (list 'read-line (string-length line) pos)) (newline))
(peek-char port)
(let ((n (check-string (read-string 10 port))))
  (write (list 'after-peek n pos)) (newline))

;; More than is left.
(let ((n (check-string (read-string 100000 port))))
  (write (list 'rest n pos)) (newline))

;; The end of the file. (let* orders the reads: chibi evaluates arguments right to left.)
(let* ((a (eof-object? (peek-char port)))
       (b (eof-object? (read-char port)))
       (c (eof-object? (read-string 10 port)))
       (d (read-string 0 port)))
  (write (list 'eof a b c d)) (newline))
(close-port port)

;; A string port, in chunks of 3 (a surrogate pair in UTF-16 at most chunks' ends).
(let* ((p (open-input-string (make-string 10 #\x1F600)))
       (a (read-string 3 p))
       (b (read-string 3 p))
       (c (read-string 3 p))
       (d (read-string 3 p))
       (e (read-string 3 p)))
  (write (list 'string-port (string-length a) (string-length b) (string-length c) d (eof-object? e))))
(newline)

(write (list 'wrong wrong)) (newline)
(delete-file file)
