;; Exceptions (R7RS §6.11, design.md D8): handlers live in DynEnv.handlers, innermost first. Calling
;; a handler doesn't unwind: it runs on top of the raise, with the outer handlers installed.
;; Primitive errors reach raise from the call site that called the primitive (D8).

(define (%with-handlers env handlers)
  (%make-dynamic-env (%dynamic-env-winders env) (%dynamic-env-parameterization env) handlers))

(define (with-exception-handler handler thunk)
  (let ((outer (%current-dynamic-env)))
    (%set-dynamic-env! (%with-handlers outer (cons handler (%dynamic-env-handlers outer))))
    (call-with-values thunk
      (lambda results (%set-dynamic-env! outer) (apply values results)))))

(define (raise obj)
  (let* ((env (%current-dynamic-env))
         (handlers (%dynamic-env-handlers env)))
    (if (null? handlers)
        (%raise-uncaught obj)
        (begin
          (%set-dynamic-env! (%with-handlers env (cdr handlers)))
          ((car handlers) obj)
          ;; The handler returned from a non-continuable raise: a secondary exception, in the
          ;; handler's dynamic environment.
          (raise (%make-error-object "exception handler returned from a non-continuable raise" (list obj)))))))

(define (raise-continuable obj)
  (let* ((env (%current-dynamic-env))
         (handlers (%dynamic-env-handlers env)))
    (if (null? handlers)
        (%raise-uncaught obj)
        (begin
          (%set-dynamic-env! (%with-handlers env (cdr handlers)))
          (call-with-values (lambda () ((car handlers) obj))
            (lambda results (%set-dynamic-env! env) (apply values results)))))))

(define (error message . irritants)
  (raise (%make-error-object message irritants)))

(%set-raise! raise)

;; guard (R7RS §4.2.7, design.md D4): the body runs under a prompt with a fresh tag and a handler.
;; On a raise, the handler captures up to that prompt (just the frames between the raise and the
;; guard) and aborts to it, which rewinds to the guard's dynamic environment; the clauses then run
;; in the guard's continuation. If none matches, the captured segment is reinstated, under a new
;; prompt with the same tag, and raise-continuable re-raises in the raise's dynamic environment.
(define %guard-raised (list 'raised))

(define (%guard body clauses)
  (let ((tag (list 'guard)))
    (define (run thunk)
      (let ((r (%call-with-prompt tag thunk)))
        (if (and (pair? r) (eq? (car r) %guard-raised))
            (let ((condition (car (cdr r)))
                  (k (cdr (cdr r))))
              (clauses condition
                       (lambda ()
                         (run (lambda () (k (lambda () (raise-continuable condition))))))))
            (apply values r))))
    (run (lambda ()
           (with-exception-handler
            (lambda (condition)
              ((%capture-to-prompt tag (lambda (k) (cons %guard-raised (cons condition k))))))
            (lambda () (call-with-values body list)))))))

(define-syntax guard
  (syntax-rules ()
    ((guard (var clause ...) e1 e2 ...)
     (%guard (lambda () e1 e2 ...)
             (lambda (var reraise) (%guard-aux (reraise) clause ...))))))

(define-syntax %guard-aux
  (syntax-rules (else =>)
    ((%guard-aux reraise (else result1 result2 ...))
     (begin result1 result2 ...))
    ((%guard-aux reraise (test => result))
     (let ((temp test)) (if temp (result temp) reraise)))
    ((%guard-aux reraise (test => result) clause1 clause2 ...)
     (let ((temp test)) (if temp (result temp) (%guard-aux reraise clause1 clause2 ...))))
    ((%guard-aux reraise (test))
     (or test reraise))
    ((%guard-aux reraise (test) clause1 clause2 ...)
     (let ((temp test)) (if temp temp (%guard-aux reraise clause1 clause2 ...))))
    ((%guard-aux reraise (test result1 result2 ...))
     (if test (begin result1 result2 ...) reraise))
    ((%guard-aux reraise (test result1 result2 ...) clause1 clause2 ...)
     (if test (begin result1 result2 ...) (%guard-aux reraise clause1 clause2 ...)))))
