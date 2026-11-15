;; limits: -Xss256k -Xmx64m
(import (scheme base) (scheme write))

(define befores 0)
(define afters 0)

(define (loop i)
  (if (= i 1000000)
      'done
      (loop (dynamic-wind
              (lambda () (set! befores (+ befores 1)))
              (lambda () (+ i 1))
              (lambda () (set! afters (+ afters 1)))))))

;; Escaping out of a dynamic-wind each iteration.
(define (escapes i)
  (if (= i 1000000)
      'done
      (escapes (call/cc (lambda (k)
                          (dynamic-wind
                            (lambda () #f)
                            (lambda () (k (+ i 1)))
                            (lambda () (set! afters (+ afters 1)))))))))

(display (loop 0)) (newline)
(display (escapes 0)) (newline)
(display befores) (newline)
(display afters) (newline)
