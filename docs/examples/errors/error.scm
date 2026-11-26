(import (scheme base))

(define (average numbers)
  (if (null? numbers)
      (error "average: no numbers in" numbers)
      (/ (apply + numbers) (length numbers))))

(average '())
