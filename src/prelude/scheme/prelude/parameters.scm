;; Parameters (R7RS §4.2.6): DynEnv.parameterization is a list of (key . value) bindings, innermost
;; first. A parameter is a procedure whose key is a fresh pair holding its global value.

(define %parameter-tag (list 'parameter))

(define (make-parameter value . converter)
  (let* ((convert (if (pair? converter) (car converter) (lambda (x) x)))
         (key (cons 'parameter (convert value))))
    (lambda args
      (cond ((null? args)
             (cdr (or (assq key (%dynamic-env-parameterization (%current-dynamic-env))) key)))
            ((eq? (car args) %parameter-tag) (cons key convert))
            ;; (p v) sets the innermost binding (R7RS leaves it to the implementation).
            (else (set-cdr! (or (assq key (%dynamic-env-parameterization (%current-dynamic-env))) key)
                            (car args)))))))

;; The converters run in the outer dynamic environment, before any binding takes effect.
(define (%parameterize params new-values body)
  (let* ((outer (%current-dynamic-env))
         (bindings (map (lambda (p v)
                          (let ((info (p %parameter-tag)))
                            (cons (car info) ((cdr info) v))))
                        params new-values)))
    (%set-dynamic-env! (%make-dynamic-env (%dynamic-env-winders outer)
                                          (append bindings (%dynamic-env-parameterization outer))
                                          (%dynamic-env-handlers outer)))
    (call-with-values body
      (lambda results (%set-dynamic-env! outer) (apply values results)))))

(define-syntax parameterize
  (syntax-rules ()
    ((parameterize ((param value) ...) body1 body2 ...)
     (%parameterize (list param ...) (list value ...) (lambda () body1 body2 ...)))))
