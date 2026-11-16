;; Circular lists (M7-06, design.md D9): list? is #f, bounded walks and map/for-each with a finite
;; list stop, and procedures that need a list raise instead of looping. chibi-scheme raises for
;; these too; it loops on others (reverse, append, memq without the element), which
;; ListVectorTest covers.
(import (scheme base) (scheme write))

(define c (list 1 2 3))
(set-cdr! (cddr c) c)

(define (try name thunk)
  (display name)
  (display ": ")
  (display (guard (e ((error-object? e) 'error) (#t 'raised)) (thunk)))
  (newline))

(try "list?" (lambda () (list? c)))
(try "map with a finite list" (lambda () (map + c '(10 20 30 40))))
(try "for-each with a finite list" (lambda () (let ((n 0)) (for-each (lambda (x y) (set! n (+ n x))) c '(a b c d e)) n)))
(try "memv, found" (lambda () (car (memv 3 c))))
(try "list-ref" (lambda () (list-ref c 100)))
(try "length" (lambda () (length c)))
(try "apply" (lambda () (apply + c)))
(try "list->vector" (lambda () (list->vector c)))
(try "list->string" (lambda () (list->string c)))
(display c)
(newline)
(write c)
(newline)
