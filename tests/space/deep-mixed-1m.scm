(import (scheme base) (scheme write))

;; A 1,000,000-deep non-tail recursion whose levels nest through `apply`, `map`,
;; `call-with-values`, `dynamic-wind`, `guard` and `with-exception-handler` in turn, with several
;; values live across each call: the frames between two Scheme frames differ at each level
;; (design.md "Stack-depth spill"). `//tests:space` runs it under -Xss256k, and
;; `//tests:space_native` through the native launcher on stacks from 256 KB to 256 MB.
(define (mixed i)
  (if (= i 0)
      0
      (let ((a (* i 2)) (b (* i 3)) (c (- i 5)))
        (+ (- a b c)
           (case (modulo i 6)
             ((0) (+ 1 (apply mixed (list (- i 1)))))
             ((1) (+ 1 (car (map mixed (list (- i 1))))))
             ((2) (+ 1 (call-with-values (lambda () (mixed (- i 1))) (lambda (x) x))))
             ((3) (+ 1 (dynamic-wind (lambda () #f) (lambda () (mixed (- i 1))) (lambda () #f))))
             ((4) (+ 1 (guard (e (#t 0)) (mixed (- i 1)))))
             (else (+ 1 (with-exception-handler (lambda (e) 0) (lambda () (mixed (- i 1)))))))
           b (- a) c))))

(display (mixed 1000000))
(newline)
