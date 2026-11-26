;; The control features and exceptions area (AGENTS.md "Areas"): dynamic-wind and the single rewind
;; (design.md "Dynamic environment", D5), call-with-values, exceptions and guard (D8, D4), and
;; parameters.
;;
;; A winder is (before . (after . outer)): its thunks, and the dynamic environment outside it, in
;; which both thunks run. DynEnv.winders is a list of winders, innermost first; nothing but
;; dynamic-wind and %rewind changes it.

(define (dynamic-wind before thunk after)
  (before)
  ((lambda (outer)
     (%set-dynamic-env!
      (%make-dynamic-env (cons (cons before (cons after outer)) (%dynamic-env-winders outer))
                         (%dynamic-env-parameterization outer)
                         (%dynamic-env-handlers outer)))
     ((lambda (result)
        (%set-dynamic-env! outer)
        (after)
        result)
      (thunk)))
   (%current-dynamic-env)))

;; Every change of control goes through here: run the after-thunks from `from` up to the common
;; ancestor (innermost first), then the before-thunks down to `to` (outermost first), each in its own
;; dynamic environment; then install `to`.
(define (%rewind from to)
  (define (len l n) (if (null? l) n (len (cdr l) (+ n 1))))
  (define (drop l n) (if (= n 0) l (drop (cdr l) (- n 1))))
  (define (common a b) (if (eq? a b) a (common (cdr a) (cdr b))))
  (define from-winders (%dynamic-env-winders from))
  (define to-winders (%dynamic-env-winders to))
  (define from-length (len from-winders 0))
  (define to-length (len to-winders 0))
  (define base
    (common (if (> from-length to-length) (drop from-winders (- from-length to-length)) from-winders)
            (if (> to-length from-length) (drop to-winders (- to-length from-length)) to-winders)))
  (define (unwind l)
    (if (eq? l base)
        #t
        (begin (%set-dynamic-env! (cddr (car l)))
               ((cadr (car l)))
               (unwind (cdr l)))))
  (define (wind l)
    (if (eq? l base)
        #t
        (begin (wind (cdr l))
               (%set-dynamic-env! (cddr (car l)))
               ((car (car l))))))
  (unwind from-winders)
  (wind to-winders)
  (%set-dynamic-env! to))

;; Called by the prompt driver to hand a captured continuation k to the receiver of call/cc or
;; %capture-to-prompt: rewinds from the environment at capture time to the one the receiver runs in
;; (they differ when a capture aborts to a prompt), then calls the receiver with a procedure for k.
(define (%receive receiver k from to)
  (define (continuation . values) (%continue k values))
  (%rewind from to)
  (receiver continuation))

;; Invoking a continuation. A full continuation (call/cc) replaces the current one: rewind into its
;; environment, then jump. A composable one (D4) runs on top of the current one and returns here:
;; rewind into its environment, run it, then rewind back.
(define (%continue k values)
  (if (%continuation-composable? k)
      ((lambda (here)
         ((lambda (ignored)
            ((lambda (result)
               (%rewind (%current-dynamic-env) here)
               result)
             (%reinstate k values)))
          (%rewind here (%continuation-dynamic-env k))))
       (%current-dynamic-env))
      (begin (%rewind (%current-dynamic-env) (%continuation-dynamic-env k))
             (%apply-continuation k values))))

;; Invoking an escape-only continuation (%call/ec, M5-04) from another dynamic environment than its
;; call/cc's: rewind to that environment first, then invoke it again, which then escapes at once.
(define (%escape k env values)
  (%rewind (%current-dynamic-env) env)
  (apply k values))

(%set-escape! %escape)

;; An error that no handler catches leaves a root prompt (a program, a REPL input, an embedder's
;; call, design.md D8): control leaves the dynamic extent of every dynamic-wind it skipped, so the
;; prompt runs their after-thunks here, innermost first, each in its own dynamic environment, then
;; lets the error go on when this returns its token.
(define (%unwind-root to token)
  (%rewind (%current-dynamic-env) to)
  token)

(%set-unwind-root! %unwind-root)

;; call-with-values calls the producer in a non-tail position, so it lives here (D5); apply makes
;; the call to the consumer a tail call.
(define (call-with-values producer consumer)
  (apply consumer (%values->list (producer))))

;; The prompt driver hands captured continuations to receivers through %receive.
(%set-receive! %receive)

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
          (raise (%make-error-object "raise: the exception handler returned from a non-continuable raise" (list obj)))))))

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
