(import (scheme base) (scheme write) (scheme read) (scheme file))

;; chibi writes bytevector elements in hex (#u8(#x41)), so bytevectors are shown as their bytes,
;; read back through a bytevector port.
(define (show-bytes bv)
  (let ((p (open-input-bytevector bv)))
    (display "bytes:")
    (let loop ()
      (let ((b (read-u8 p)))
        (if (not (eof-object? b))
            (begin (display " ") (display b) (loop)))))
    (newline)))

;; String ports: read, read-char, peek-char, read-line, read-string (M4-06).
(define in (open-input-string "(define x '(1 . 2)) #(a \"b\" #\\c) 3/4 -2.5e3 #!fold-case HELLO\nline two\nrest"))
(write (read in)) (newline)
(write (read in)) (newline)
(write (read in)) (newline)
(write (read in)) (newline)
(write (read in)) (newline)
(write (read-line in)) (newline)
(write (peek-char in)) (newline)
(write (read-line in)) (newline)
(write (read-string 2 in)) (newline)
(write (read-string 10 in)) (newline)
(write (eof-object? (read-char in))) (newline)
(write (eof-object? (read in))) (newline)

;; Read errors are read-error?s.
(write (guard (e ((read-error? e) 'read-error)) (read (open-input-string "(1 2")))) (newline)
(write (guard (e ((read-error? e) 'read-error)) (read (open-input-string "(1 . )")))) (newline)

;; Output to string ports, including labels for shared and circular structure.
(define out (open-output-string))
(define shared (list 1 2))
(write-shared (list shared shared) out)
(write-char #\space out)
(write-simple (list shared shared) out)
(write-string " partial " out 1 5)
(display '(sym 2) out)
(display "str" out)
(display #\c out)
(write (get-output-string out)) (newline)

;; Bytevector ports.
(define bout (open-output-bytevector))
(write-u8 65 bout)
(write-bytevector #u8(66 67 68 69) bout 1 3)
(show-bytes (get-output-bytevector bout))
(define bin (open-input-bytevector #u8(1 2 3 4 5)))
(write (peek-u8 bin)) (newline)
(write (read-u8 bin)) (newline)
(show-bytes (read-bytevector 3 bin))
(show-bytes (read-bytevector 3 bin))
(write (eof-object? (read-bytevector 3 bin))) (newline)

;; The current ports are parameters.
(define captured (open-output-string))
(parameterize ((current-output-port captured))
  (display "captured")
  (write 'too))
(write (get-output-string captured)) (newline)
(write (parameterize ((current-input-port (open-input-string "from-param"))) (read))) (newline)

;; File ports, through call-with-output-file, with-input-from-file and friends.
(define file "io-ports-diff.tmp")
(call-with-output-file file
  (lambda (port)
    (write '(data "with" #\x 1.5) port)
    (newline port)
    (display "second line" port)))
(write (file-exists? file)) (newline)
(write (call-with-input-file file read)) (newline)
(write (with-input-from-file file (lambda () (read) (read-char) (read-line)))) (newline)
(with-output-to-file file (lambda () (write-string "overwritten")))
(write (call-with-input-file file read-line)) (newline)
(let ((p (open-binary-output-file file)))
  (write-bytevector #u8(0 1 254 255) p)
  (close-port p))
(let ((p (open-binary-input-file file)))
  (show-bytes (read-bytevector 100 p))
  (close-input-port p))
(delete-file file)
(write (file-exists? file)) (newline)
(write (guard (e ((file-error? e) 'file-error)) (open-input-file file))) (newline)

;; Closed ports.
(define closed (open-input-string "abc"))
(close-port closed)
(write (input-port-open? closed)) (newline)
(write (guard (e (#t 'error)) (read-char closed))) (newline)
