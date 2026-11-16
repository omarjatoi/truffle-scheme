(import (scheme base) (scheme char) (scheme write))

;; M4-02: characters, strings and symbols (R7RS §6.5–6.7).
(define (show x) (write x) (newline))

;; Symbols
(show (list (symbol=? 'a 'a 'a) (symbol=? 'a 'b) (symbol->string 'abc) (string->symbol "xyz")))
(show (eq? 'hello (string->symbol (string-append "hel" "lo"))))

;; Characters
(show (map char->integer (list #\a (integer->char 955) (char-upcase #\z) (char-downcase #\Q) (char-foldcase #\x3a3))))
(show (list (char<? #\a #\b #\c) (char-ci=? #\x #\X) (char>=? #\b #\a #\a) (char-ci<? #\Z #\a)))
(show (map (lambda (c) (list (char-alphabetic? c) (char-numeric? c) (char-whitespace? c)
                             (char-upper-case? c) (char-lower-case? c) (digit-value c)))
           (list #\a #\A #\7 #\space #\tab #\x0664 #\x3bb #\x2028 #\-)))

;; Strings
(define s (make-string 6 #\-))
(string-set! s 0 #\a)
(string-copy! s 1 "bcde" 1 3)
(string-fill! s #\z 4)
(show s)
(show (list (string-length s) (string-ref s 1) (substring s 1 3) (string-copy s 2) (string->list s 3 5)))
(show (list (string #\x #\y) (list->string (list #\1 #\2)) (string-append "foo" "" "bar")))
(show (list (string<? "apple" "banana" "cherry") (string=? "a" "a" "b") (string-ci=? "HeLLo" "hello")
            (string>? "b" "abc") (string<=? "ab" "ab" "abc") (string-ci>=? "ABC" "abc")))
(show (list (string-upcase "Hello, World!") (string-downcase "Hello, World!") (string-foldcase "Hello")))
(show (map string-length (list (string-upcase "stra\xdf;e") (string-foldcase "stra\xdf;e") (string-downcase "\x130;"))))

;; Wide strings: a character above 255 widens the storage.
(define w (string-copy "abc"))
(string-set! w 1 (integer->char #x1F700))
(show (map char->integer (string->list w)))
(show (string=? w (string #\a (integer->char #x1F700) #\c)))

;; string-map and string-for-each
(show (string-map char-upcase "abc"))
(show (string-map (lambda (a b) (if (char<? a b) a b)) "adcz" "bbbbbb"))
(let ((n 0))
  (string-for-each (lambda (c d) (set! n (+ n (char->integer c) (- (char->integer d) 48)))) "ab" "123")
  (show n))
