(import (scheme base) (scheme cxr) (scheme write))

;; M2-01: list procedures from the prelude (src/prelude), core forms only.
(define l (list 1 2 3 4))
(write l)
(newline)
(write (list (cadr l) (caddr l) (cddr l) (cdddr l) (caar (list l)) (cdar (list l))))
(newline)
(write (list (length l) (length '()) (reverse l) (reverse '())))
(newline)
(write (list))
(newline)
