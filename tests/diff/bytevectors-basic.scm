(import (scheme base) (scheme write))

;; M4-04: bytevectors (R7RS §6.9).
;; Bytevectors are shown as lists: chibi writes their bytes in hex, which R7RS leaves open.
(define (bv->list bv)
  (let loop ((i (- (bytevector-length bv) 1)) (acc '()))
    (if (< i 0) acc (loop (- i 1) (cons (bytevector-u8-ref bv i) acc)))))
(define (convert x)
  (cond ((bytevector? x) (cons 'bytevector (bv->list x)))
        ((pair? x) (cons (convert (car x)) (convert (cdr x))))
        (else x)))
(define (show x) (write (convert x)) (newline))

(define bv (make-bytevector 4 7))
(show bv)
(bytevector-u8-set! bv 0 255)
(show (list (bytevector-u8-ref bv 0) (bytevector-length bv) (bytevector? bv) (bytevector? #(1))))
(show (bytevector 1 2 3))
(show (bytevector))

(show (list (bytevector-copy #u8(1 2 3 4)) (bytevector-copy #u8(1 2 3 4) 2) (bytevector-copy #u8(1 2 3 4) 1 3)))

;; copy! in both directions, overlapping.
(let ((b (bytevector 1 2 3 4 5 6)))
  (bytevector-copy! b 2 b 0 4)
  (show b)
  (bytevector-copy! b 0 b 2)
  (show b))

(show (bytevector-append #u8(1) #u8() #u8(2 3)))
(show (bytevector-append))

;; A copy of a literal is fresh and mutable; the literal is unchanged.
(define lit #u8(10 20 30))
(define c (bytevector-copy lit))
(bytevector-u8-set! c 1 99)
(show (list lit c (equal? lit #u8(10 20 30)) (eq? lit c)))

;; UTF-8, with ranges and multi-byte characters.
(show (utf8->string #u8(104 105)))
(show (utf8->string #u8(0 #xCE #xBB 33 0) 1 4))
(show (string->utf8 "h\xE9;llo" 1 3))
(show (string->utf8 "\x1F600;"))
(show (equal? "a\x3BB;\x1F600;" (utf8->string (string->utf8 "a\x3BB;\x1F600;"))))

;; Errors are catchable.
(show (guard (e (#t 'caught)) (bytevector-u8-ref #u8(1 2) 5)))
(show (guard (e (#t 'caught)) (bytevector 300)))

;; A loop over a large bytevector.
(define big (make-bytevector 100000 0))
(let loop ((i 0))
  (when (< i (bytevector-length big))
    (bytevector-u8-set! big i (if (< i 256) i 255))
    (loop (+ i 1))))
(show (let loop ((i 0) (sum 0))
        (if (= i (bytevector-length big)) sum (loop (+ i 1) (+ sum (bytevector-u8-ref big i))))))
