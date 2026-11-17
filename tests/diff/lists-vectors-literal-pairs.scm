(import (scheme base) (scheme write))

;; R7RS §3.4: literal constants are immutable, and storing into one is an error (design.md D1, D9).
;; Only the cases chibi-scheme also signals: it makes quasiquote's constant parts and syntax-rules
;; templates' quoted data mutable, where R7RS makes them literals (ListVectorTest covers those).
(define (try name thunk)
  (write (list name (guard (e ((error-object? e) 'error)) (thunk) 'ok)))
  (newline))

(try 'quote-set-car (lambda () (set-car! '(1 2) 9)))
(try 'quote-set-cdr (lambda () (set-cdr! (quote (a . b)) 1)))
(try 'quote-list-set (lambda () (list-set! '(1 2) 0 9)))
(try 'nested (lambda () (set-car! (cadr '(1 (2 3))) 9)))
(try 'vector-element (lambda () (set-car! (vector-ref #((1) 2) 0) 9)))
(define (g) '(constant-list))
(try 'procedure-result (lambda () (set-car! (g) 3)))
(try 'append-shares-last (lambda () (set-car! (cdr (append (list 1) '(2))) 9)))

;; Pairs made at run time stay mutable, copies of literals included.
(try 'list-copy (lambda () (set-car! (list-copy '(1 2)) 9)))
(try 'append-copies (lambda () (set-car! (append '(1) '(2)) 9)))
(try 'rest-list (lambda () (apply (lambda args (set-car! args 9)) '(1 2))))
(try 'cons (lambda () (set-cdr! (cons 1 2) 3)))
(define l (list 1 2 3))
(list-set! l 1 'x)
(write l)
(newline)
(write (list (equal? '(1 (2)) (list 1 (list 2))) (let ((x '(a))) (eq? x x))))
(newline)
