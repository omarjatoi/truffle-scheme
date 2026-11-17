;; Improper lists (the §6 audit's follow-up, design.md D9): R7RS §6.4 and §6.10 give these
;; procedures lists (alists), so a walk that reaches a tail other than () raises, where chibi-scheme
;; raises too: assv and assoc, and map and for-each. memq, memv, member and assq return #f there in
;; chibi, so ListVectorTest covers them.
(import (scheme base) (scheme write))

(define (try name thunk)
  (display name)
  (display ": ")
  (display (guard (e ((error-object? e) 'error) (#t 'raised)) (thunk)))
  (newline))

(try "assv" (lambda () (assv 9 '((1 . a) . 5))))
(try "assoc" (lambda () (assoc "z" '(("a" . 1) . 5))))
(try "assq, found" (lambda () (assq 'b '((a . 1) (b . 2) . c))))
(try "map" (lambda () (map car '((1) (2) . 3))))
(try "map of a non-list" (lambda () (map car 5)))
(try "map, several lists" (lambda () (map + '(1 2 3) '(10 20 . 30))))
(try "map, lists ending together" (lambda () (map list '(1 2) '(a b . c))))
(try "map, the shorter list ends first" (lambda () (map + '(1 2) '(10 20 30 . 40))))
(try "for-each" (lambda () (for-each car '((1) . 3))))
(try "for-each of a non-list" (lambda () (for-each car 'x)))
(try "for-each, several lists" (lambda () (for-each + '(1 2) '(1 . 2))))
(try "for-each runs f first"
     (lambda ()
       (let ((acc '()))
         (guard (e (#t acc)) (for-each (lambda (x) (set! acc (cons x acc))) '(1 2 . 3))))))
(try "map, proper" (lambda () (map + '(1 2) '(10 20))))
