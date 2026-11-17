(import (scheme base) (scheme write) (scheme file) (scheme char))

;; read-line (R7RS 6.13.2) over a file larger than a port's buffer: lines of many lengths, some
;; empty, ending in LF or CR LF, whose characters take one to four bytes in UTF-8 (and a surrogate
;; pair in UTF-16), mixed with peek-char, read-char and read-string, and a last line without a
;; line ending. Then string ports, where a CR alone also ends a line. (chibi-scheme reads a file's
;; line 8 KB at a time: it splits a longer line, and fails on a character split across the 8 KB,
;; so no line here is longer than that; IoTest reads longer ones. Nor does a CR alone end a line
;; of a file for it.)
(define file "io-read-line-diff.tmp")

(define chars (vector #\a #\b #\x00E9 #\x00FF #\x03BB #\x1F600 #\space #\z #\x4E2D))
(define (line-char i j) (vector-ref chars (modulo (+ i j (quotient j 7)) (vector-length chars))))

;; Line i's length: mostly short, some empty, some of a few KB.
(define (line-length i)
  (cond ((= 0 (modulo i 11)) 0)
        ((= 0 (modulo i 37)) (+ 1000 (modulo (* 97 i) 1500)))
        (else (modulo (* i 53) 150))))

(define count 400)
(define (expected-line i)
  (let ((s (make-string (line-length i))))
    (do ((j 0 (+ j 1))) ((= j (string-length s)) s) (string-set! s j (line-char i j)))))
(define (ending i) (if (odd? (quotient i 3)) "\r\n" "\n"))

(call-with-output-file file
  (lambda (p)
    (do ((i 0 (+ i 1))) ((= i count))
      (write-string (expected-line i) p)
      (when (< i (- count 1)) (write-string (ending i) p)))))

(define port (open-input-file file))
(define wrong '())
(define (check i what got expected)
  (unless (equal? got expected) (set! wrong (cons (list i what) wrong))))

;; Each line read whole, or a character or a few first: a peek, a read-char, a read-string.
(do ((i 0 (+ i 1))) ((= i count))
  (let* ((line (expected-line i))
         (n (string-length line))
         (way (modulo i 4)))
    (cond ((or (= way 0) (= n 0))
           (check i 'line (read-line port) line))
          ((= way 1)
           (let* ((c (peek-char port)) (l (read-line port)))
             (check i 'peek c (string-ref line 0))
             (check i 'line l line)))
          ((= way 2)
           (let* ((c (read-char port)) (l (read-line port)))
             (check i 'char c (string-ref line 0))
             (check i 'line l (substring line 1 n))))
          (else
           (let* ((k (min n 3)) (s (read-string k port)) (l (read-line port)))
             (check i 'string s (substring line 0 k))
             (check i 'line l (substring line k n)))))))
(write (list 'file-lines count 'wrong (reverse wrong))) (newline)
(write (list 'at-end (eof-object? (read-line port)) (eof-object? (peek-char port)) (eof-object? (read-line port))))
(newline)
(close-port port)

;; Line lengths and first characters, so a difference shows where.
(call-with-input-file file
  (lambda (p)
    (let loop ((i 0) (total 0))
      (let ((l (read-line p)))
        (if (eof-object? l)
            (begin (write (list 'lines i 'characters total)) (newline))
            (begin
              (when (< i 30)
                (write (list i (string-length l) (if (> (string-length l) 0) (char->integer (string-ref l 0)) 'empty)))
                (newline))
              (loop (+ i 1) (+ total (string-length l)))))))))

(define (all-lines p)
  (let loop ((acc '()))
    (let ((l (read-line p)))
      (if (eof-object? l) (reverse acc) (loop (cons l acc))))))

;; String ports: LF, CR LF and a CR alone, empty lines, no last line ending, nothing at all.
(for-each
 (lambda (s) (write (all-lines (open-input-string s))) (newline))
 (list "a\nb\r\nc\rd\n\n\r\r\ne" "" "x" "x\n" "\r" "\n\n" "\r\n\r\n" "\x1F600;\r\n\xE9;\xFF;\n\x4E2D;z"
       (string-append (make-string 300 #\x4E2D) "\r\n" (make-string 1000 #\a) "\r" (make-string 129 #\b))))

;; A peek between read-lines sees the next line's first character, a line ending among them.
(let* ((p (open-input-string "ab\r\n\r\ncd\n\nef"))
       (a (read-line p)) (b (peek-char p)) (c (read-line p)) (d (peek-char p))
       (e (read-line p)) (f (peek-char p)) (g (read-line p)) (h (read-line p)) (i (read-line p)))
  (write (list a b c d e f g h (eof-object? i))) (newline))

(delete-file file)
