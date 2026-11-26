; A guard none of whose clauses match: it re-raises in the raise's continuation, reinstated.
(import (scheme base) (scheme eval) (scheme load) (scheme process-context))
(define (f)
  (list (guard (e ((string? e) 'no))
          (list RAISE))))
(define (g) (list (f)))
(g)
