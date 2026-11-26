(import (scheme base) (scheme write))

;; Running out of memory isn't a Scheme error: no handler sees it and no after-thunk runs, as for
;; a resource limit that cancels (design.md D8, "Running out of memory"); //tests:memory runs it
;; under a 64 MB heap.
(display "start")
(newline)
(define (grow l) (grow (cons (make-vector 1000 0) l)))
(with-exception-handler
 (lambda (e) (display "handled") (newline) 0)
 (lambda ()
   (guard (e (#t (display "caught") (newline)))
     (dynamic-wind
      (lambda () #f)
      (lambda () (grow '()))
      (lambda () (display "after") (newline))))))
(display "end")
