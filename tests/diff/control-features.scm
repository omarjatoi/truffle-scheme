;; Booleans, control features and exceptions (R7RS §6.3, §6.10, §6.11): M4-05.
(import (scheme base) (scheme write))

(define (show x) (write x) (newline))

;; §6.3
(show (list (boolean? #f) (boolean? 0) (boolean? '())))
(show (list (boolean=? #t #t) (boolean=? #f #f #f) (boolean=? #t #t #f)))

;; §6.10: procedure?, apply
(show (list (procedure? car) (procedure? 'car) (procedure? (lambda (x) x))
            (call-with-current-continuation procedure?)))
(show (apply + 1 2 '(3 4)))
(show (apply list '()))
(show (let loop ((i 0)) (if (< i 100000) (apply loop (list (+ i 1))) i)))
(show (guard (e (#t 'apply-error)) (apply + 1 2)))

;; map and for-each over several lists, the shortest deciding.
(show (map (lambda (a b c) (+ a b c)) '(1 2) '(10 20 30) '(100 200)))
(for-each (lambda (a b) (show (list a b))) '(1 2 3) '(x y))

;; Re-entering map's procedure leaves an earlier result alone.
(show (let ((k #f) (results '()))
        (let ((r (map (lambda (x) (call/cc (lambda (c) (if (= x 2) (set! k c)) x)))
                      '(1 2 3))))
          (set! results (cons r results))
          (if (< (length results) 3) (k (* 10 (length results))))
          (reverse results))))

;; Values
(show (call-with-values values list))
(show (call-with-values (lambda () (values 1 2 3)) list))
(show (call-with-values * -))
(show (call-with-values
          (lambda () (dynamic-wind (lambda () #f) (lambda () (values 1 2)) (lambda () #f)))
        list))

;; dynamic-wind: re-entry runs the before-thunks again, outermost first.
(show (let ((trail '()) (k #f) (n 0))
        (define (note x) (set! trail (cons x trail)))
        (dynamic-wind
         (lambda () (note 'in1))
         (lambda ()
           (dynamic-wind
            (lambda () (note 'in2))
            (lambda () (call/cc (lambda (c) (set! k c))) (note 'body))
            (lambda () (note 'out2))))
         (lambda () (note 'out1)))
        (set! n (+ n 1))
        (if (< n 3) (k #f))
        (reverse trail)))

;; §6.11
(show (with-exception-handler
       (lambda (e) 10)
       (lambda () (+ 1 (raise-continuable 'oops)))))
(show (guard (e (#t (list 'outer e)))
        (with-exception-handler
         (lambda (e) (raise (list 'inner e)))
         (lambda () (raise 'x)))))
(show (guard (e ((symbol? e) (list 'symbol e)) ((string? e) (list 'string e)))
        (raise "s")))
(show (guard (e ((error-object? e) (list (error-object-message e) (error-object-irritants e))))
        (error "message" 1 'two "three")))
(show (guard (e (#t (list (error-object? e) (file-error? e) (read-error? e))))
        (raise 'not-an-error-object)))
(show (guard (e ((error-object? e) 'primitive-error)) (vector-ref (vector) 0)))
(show (call-with-current-continuation
       (lambda (k)
         (with-exception-handler
          (lambda (e) (k (list 'escaped e)))
          (lambda () (+ 1 (raise 'an-error)))))))
(show (guard (e ((assq 'a e) => cdr) ((assq 'b e)))
        (raise (list (cons 'b 23)))))

;; Parameters
(define p (make-parameter 10 (lambda (x) (* x 2))))
(show (list (p) (parameterize ((p 3)) (p)) (p)))
