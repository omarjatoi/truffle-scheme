(import (scheme base))

(define (check-age age)
  (if (negative? age)
      (raise (list 'negative-age age))
      age))

(check-age -3)
