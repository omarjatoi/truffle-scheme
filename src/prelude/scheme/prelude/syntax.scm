;; R7RS §7.3's derived expression types, as syntax-rules (design.md D3). Their keywords and
;; procedures refer to the prelude's own bindings, so they work whatever the use site binds.

(define-syntax let
  (syntax-rules ()
    ((let ((name val) ...) body1 body2 ...)
     ((lambda (name ...) body1 body2 ...) val ...))
    ((let tag ((name val) ...) body1 body2 ...)
     ((letrec ((tag (lambda (name ...) body1 body2 ...))) tag) val ...))))

(define-syntax let*
  (syntax-rules ()
    ((let* () body1 body2 ...)
     (let () body1 body2 ...))
    ((let* ((name1 val1) (name2 val2) ...) body1 body2 ...)
     (let ((name1 val1))
       (let* ((name2 val2) ...) body1 body2 ...)))))

;; letrec* is a valid letrec: R7RS leaves the order of letrec's inits unspecified.
(define-syntax letrec
  (syntax-rules ()
    ((letrec ((var init) ...) body1 body2 ...)
     (letrec* ((var init) ...) body1 body2 ...))))

(define-syntax and
  (syntax-rules ()
    ((and) #t)
    ((and test) test)
    ((and test1 test2 ...) (if test1 (and test2 ...) #f))))

(define-syntax or
  (syntax-rules ()
    ((or) #f)
    ((or test) test)
    ((or test1 test2 ...) (let ((x test1)) (if x x (or test2 ...))))))

(define-syntax cond
  (syntax-rules (else =>)
    ((cond (else result1 result2 ...))
     (begin result1 result2 ...))
    ((cond (test => result))
     (let ((temp test)) (if temp (result temp))))
    ((cond (test => result) clause1 clause2 ...)
     (let ((temp test))
       (if temp (result temp) (cond clause1 clause2 ...))))
    ((cond (test)) test)
    ((cond (test) clause1 clause2 ...)
     (let ((temp test))
       (if temp temp (cond clause1 clause2 ...))))
    ((cond (test result1 result2 ...))
     (if test (begin result1 result2 ...)))
    ((cond (test result1 result2 ...) clause1 clause2 ...)
     (if test (begin result1 result2 ...) (cond clause1 clause2 ...)))))

(define-syntax case
  (syntax-rules (else =>)
    ((case (key ...) clauses ...)
     (let ((atom-key (key ...)))
       (case atom-key clauses ...)))
    ((case key (else => result))
     (result key))
    ((case key (else result1 result2 ...))
     (begin result1 result2 ...))
    ((case key ((atoms ...) => result))
     (if (memv key '(atoms ...)) (result key)))
    ((case key ((atoms ...) => result) clause clauses ...)
     (if (memv key '(atoms ...)) (result key) (case key clause clauses ...)))
    ((case key ((atoms ...) result1 result2 ...))
     (if (memv key '(atoms ...)) (begin result1 result2 ...)))
    ((case key ((atoms ...) result1 result2 ...) clause clauses ...)
     (if (memv key '(atoms ...)) (begin result1 result2 ...) (case key clause clauses ...)))))

(define-syntax when
  (syntax-rules ()
    ((when test result1 result2 ...) (if test (begin result1 result2 ...)))))

(define-syntax unless
  (syntax-rules ()
    ((unless test result1 result2 ...) (if (not test) (begin result1 result2 ...)))))

;; The loop's self tail call compiles to a loop (design.md "Calls and tail calls").
(define-syntax do
  (syntax-rules ()
    ((do ((var init step ...) ...) (test expr ...) command ...)
     (letrec ((loop (lambda (var ...)
                      (if test
                          (begin (if #f #f) expr ...)
                          (begin command ... (loop (do "step" var step ...) ...))))))
       (loop init ...)))
    ((do "step" x) x)
    ((do "step" x y) y)))

(define-syntax let-values
  (syntax-rules ()
    ((let-values (binding ...) body0 body1 ...)
     (let-values "bind" (binding ...) () (begin body0 body1 ...)))
    ((let-values "bind" () tmps body)
     (let tmps body))
    ((let-values "bind" ((b0 e0) binding ...) tmps body)
     (let-values "mktmp" b0 e0 () (binding ...) tmps body))
    ((let-values "mktmp" () e0 args bindings tmps body)
     (call-with-values (lambda () e0) (lambda args (let-values "bind" bindings tmps body))))
    ((let-values "mktmp" (a . b) e0 (arg ...) bindings (tmp ...) body)
     (let-values "mktmp" b e0 (arg ... x) bindings (tmp ... (a x)) body))
    ((let-values "mktmp" a e0 (arg ...) bindings (tmp ...) body)
     (call-with-values (lambda () e0) (lambda (arg ... . x) (let-values "bind" bindings (tmp ... (a x)) body))))))

(define-syntax let*-values
  (syntax-rules ()
    ((let*-values () body0 body1 ...)
     (let () body0 body1 ...))
    ((let*-values (binding0 binding1 ...) body0 body1 ...)
     (let-values (binding0) (let*-values (binding1 ...) body0 body1 ...)))))


;; §7.3's define-values, except that the values are received by a lambda with the formals, so that
;; a duplicate variable is a syntax error (R7RS §5.3: "It is an error if a variable appears more
;; than once in the set of <formals>") at the top level too, where two definitions of one variable
;; would otherwise be a redefinition.
(define-syntax define-values
  (syntax-rules ()
    ((define-values () expr)
     (define dummy (call-with-values (lambda () expr) (lambda args #f))))
    ((define-values (var) expr)
     (define var expr))
    ((define-values (var0 var1 ... varn) expr)
     (begin
       (define var0 (call-with-values (lambda () expr) (lambda (var0 var1 ... varn) (list var0 var1 ... varn))))
       (define var1 (let ((v (cadr var0))) (set-cdr! var0 (cddr var0)) v)) ...
       (define varn (let ((v (cadr var0))) (set! var0 (car var0)) v))))
    ((define-values (var0 var1 ... . varn) expr)
     (begin
       (define var0 (call-with-values (lambda () expr) (lambda (var0 var1 ... . varn) (cons var0 (append (list var1 ...) varn)))))
       (define var1 (let ((v (cadr var0))) (set-cdr! var0 (cddr var0)) v)) ...
       (define varn (let ((v (cdr var0))) (set! var0 (car var0)) v))))
    ((define-values var expr)
     (define var (call-with-values (lambda () expr) list)))))

(define-syntax case-lambda
  (syntax-rules ()
    ((case-lambda (params body0 ...) ...)
     (lambda args
       (let ((len (length args)))
         (letrec-syntax
             ((cl (syntax-rules ::: ()
                    ((cl) (%error "case-lambda: no clause matches the arguments" args))
                    ((cl ((p :::) . body) . rest)
                     (if (= len (length '(p :::)))
                         (apply (lambda (p :::) . body) args)
                         (cl . rest)))
                    ((cl ((p ::: . tail) . body) . rest)
                     (if (>= len (length '(p :::)))
                         (apply (lambda (p ::: . tail) . body) args)
                         (cl . rest))))))
           (cl (params body0 ...) ...)))))))

;; Promises (R7RS §4.2.5): the space-safe reference implementation of §7.3 (design.md D9). A promise
;; is an SPromise whose state is done? and the value or thunk; forcing a delay-force chain shares
;; one state, so iterative lazy algorithms run in constant space (lazy.scm).
(define-syntax delay-force
  (syntax-rules ()
    ((delay-force expression) (%make-promise #f (lambda () expression)))))

(define-syntax delay
  (syntax-rules ()
    ((delay expression) (delay-force (%make-promise #t expression)))))
