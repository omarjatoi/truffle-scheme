;; R7RS §4 (expressions, macros) and §5 (program structure): behaviour the §4-5 audit checked
;; against the spec and its errata (r7rs.org) where neither vendored suite looks, and where
;; chibi-scheme agrees with R7RS. syntax-rules (custom ellipsis, (... ...), _, literals by binding,
;; tail and vector patterns, nested ellipses, bytevector datums per erratum 10), hygiene,
;; let-syntax/letrec-syntax, case with => (erratum 6), do, case-lambda, let*-values, internal
;; define-values and define-record-type, parameterize converters, guard re-raising with
;; raise-continuable in the raise's dynamic environment, promises, nested quasiquote, letrec*
;; (erratum 9), and import sets.
(import (prefix (scheme base) b:) (rename (scheme base) (else otherwise) (=> arrow))
        (scheme base) (scheme write) (scheme case-lambda) (scheme lazy) (scheme char) (scheme inexact))
(define (show . xs) (for-each (lambda (x) (write x) (display " ")) xs) (newline))

;; ---- sr1

;; custom ellipsis
(define-syntax my-list
  (syntax-rules ::: ()
    ((_ x :::) (list x :::))))
(show 'custom (my-list 1 2 3))

;; custom ellipsis: ... is then a plain identifier
(define-syntax dots
  (syntax-rules ::: ()
    ((_ x :::) '(x ::: ...))))
(show 'dots (dots 1 2))

;; (... ...) escape
(define-syntax make-lister
  (syntax-rules ()
    ((_ name) (define-syntax name (syntax-rules () ((_ x (... ...)) (list x (... ...))))))))
(make-lister lst)
(show 'escape (lst 4 5 6))
(define-syntax esc2
  (syntax-rules ()
    ((_ a ...) '((... (a ...)) ...))))
(show 'escape2 (esc2 1 2))

;; underscore
(define-syntax second
  (syntax-rules ()
    ((_ _ b . _) 'b)))
(show 'underscore (second 1 2 3 4))

;; underscore as literal
(define-syntax lit_
  (syntax-rules (_)
    ((m _) 'underscore)
    ((m x) 'other)))
(show 'lit_ (lit_ _) (lit_ 3))

;; tail patterns
(define-syntax tailp
  (syntax-rules ()
    ((_ a ... b c) '((a ...) b c))))
(show 'tail (tailp 1 2 3 4 5) (tailp 1 2))
(define-syntax tailp2
  (syntax-rules ()
    ((_ a ... b . c) '((a ...) b c))))
(show 'tail2 (tailp2 1 2 3 . 4) (tailp2 1 2 3))

;; vector patterns
(define-syntax vecp
  (syntax-rules ()
    ((_ #(a b ... c)) '(a (b ...) c))))
(show 'vec (vecp #(1 2 3 4)) (vecp #(1 2)))
(define-syntax vecp2
  (syntax-rules ()
    ((_ #(a ...) ...) '#((a ... 0) ...))))
(show 'vec2 (vecp2 #(1 2) #(3)))

;; nested ellipsis
(define-syntax nest
  (syntax-rules ()
    ((_ (a b ...) ...) '((b ... a) ...))))
(show 'nest (nest (1 2 3) (4) (5 6)))
(define-syntax flat
  (syntax-rules ()
    ((_ (a ...) ...) '(a ... ...))))
(show 'flat (flat (1 2) () (3 4 5)))

;; pattern var at depth 0 used inside ellipsis in template
(define-syntax pairup
  (syntax-rules ()
    ((_ k (v ...)) '((k v) ...))))
(show 'pairup (pairup x (1 2 3)))

;; literals match by binding
(define-syntax is-else
  (syntax-rules (else)
    ((_ else) 'yes)
    ((_ x) 'no)))
(show 'else (is-else else) (let ((else 1)) (is-else else)))

;; hygiene
(define-syntax my-or
  (syntax-rules () ((_) #f) ((_ e) e) ((_ e r ...) (let ((t e)) (if t t (my-or r ...))))))
(show 'hyg (let ((t 5)) (my-or #f t)))
(show 'hyg2 (let ((if list)) (my-or #f 7)))
(define-syntax swap!
  (syntax-rules () ((_ a b) (let ((tmp a)) (set! a b) (set! b tmp)))))
(show 'swap (let ((tmp 1) (other 2)) (swap! tmp other) (list tmp other)))

;; literal in pattern datum: strings, chars, numbers, bytevectors (erratum 10)
(define-syntax datum
  (syntax-rules ()
    ((_ "a") 'string) ((_ #\b) 'char) ((_ 3) 'three) ((_ #u8(1 2)) 'bv) ((_ #t) 'true) ((_ x) 'other)))
(show 'datum (datum "a") (datum #\b) (datum 3) (datum #u8(1 2)) (datum #t) (datum 3.0) (datum "b"))

;; improper list pattern against identifier
(define-syntax ip (syntax-rules () ((_ (a ... . r)) '((a ...) r))))
(show 'ip (ip (1 2 . 3)) (ip x) (ip (1 2)))

;; ellipsis following a literal
(define-syntax lit-ell (syntax-rules (=>) ((_ => ...) 'arrows) ((_ x ...) 'other)))

;; template with ellipsis after a vector
(define-syntax vt (syntax-rules () ((_ (a b) ...) (vector '#(a b) ...))))
(show 'vt (vt (1 2) (3 4)))

;; ---- sr2

;; ... in literals is a literal
(define-syntax lit-dots
  (syntax-rules (...)
    ((_ a ...) '(a lit))
    ((_ a b) '(a b two))))
(show 'litdots (lit-dots 1 ...) (lit-dots 1 2))

;; custom ellipsis also in literals => literal
(define-syntax ce-lit
  (syntax-rules ::: (:::)
    ((_ a :::) 'lit)
    ((_ a b) 'two)))
(show 'celit (ce-lit 1 :::) (ce-lit 1 2))

;; nested macro definitions with custom ellipsis to avoid escaping
(define-syntax def-lister
  (syntax-rules ()
    ((_ name) (define-syntax name (syntax-rules ::: () ((_ x :::) (list x :::)))))))
(def-lister l2)
(show 'nested-custom (l2 1 2))

;; outer ellipsis used inside inner custom-ellipsis template
(define-syntax def-consts
  (syntax-rules ()
    ((_ name v ...) (define-syntax name (syntax-rules ::: () ((_ x :::) (list v ... x :::)))))))
(def-consts c3 1 2)
(show 'mix (c3 3 4))

;; free-identifier=?: a literal that's locally bound at the macro but not at the use
(define-syntax check-else
  (syntax-rules (else) ((_ else) 'matched) ((_ x) 'nope)))
(define else-test (let ((else #f)) (check-else else)))
(show 'free-id else-test)

;; top-level define introduced by macro: hygienic (D16)
(define-syntax def-hidden
  (syntax-rules () ((_ get v) (begin (define hidden v) (define (get) hidden)))))
(define hidden 'outer)
(def-hidden get-hidden 'inner)
(show 'd16 hidden (get-hidden))

;; macro-introduced define in a body
(define-syntax def-x (syntax-rules () ((_ e) (define x e))))
(show 'body (let ((x 1)) (def-x 2) x))

;; macro defining user-named identifier in a body
(define-syntax def-named (syntax-rules () ((_ n e) (define n e))))
(show 'body2 (let () (def-named y 3) y))

;; macro use before definition in a body (definition after use of keyword)
(show 'bodymac
  (let ()
    (define-syntax m (syntax-rules () ((_) 10)))
    (define z (m))
    z))

;; shadowing a macro keyword by local variable
(show 'shadow (let ((when (lambda (a b) (list a b)))) (when 1 2)))

;; shadowing `define` in a body
(show 'shadow-define (let () (define (define x) (* x 2)) (define 21)))

;; syntax-error
(define-syntax must-be-pair
  (syntax-rules () ((_ (a . b)) 'ok) ((_ x) (syntax-error "not a pair" x))))
(show 'se (must-be-pair (1 . 2)))

;; vector template with nested ellipsis
(define-syntax vnest (syntax-rules () ((_ (a ...) ...) #(#(a ...) ...))))
(show 'vnest (vnest (1 2) (3)))

;; let-syntax scoping: inner refs to outer binding
(show 'let-syntax
  (let ((x 'outer))
    (let-syntax ((m (syntax-rules () ((_) x))))
      (let ((x 'inner))
        (m)))))
(show 'letrec-syntax
  (letrec-syntax ((my-or (syntax-rules () ((_) #f) ((_ e) e) ((_ e r ...) (let ((t e)) (if t t (my-or r ...)))))))
    (let ((x #f) (y 7) (t 8)) (my-or x y t))))
;; let-syntax: the transformer's own name isn't in scope of its rhs (non-recursive)
(show 'let-syntax-nonrec
  (let-syntax ((when (syntax-rules () ((_ a b) (if a b 'no)))))
    (let-syntax ((when (syntax-rules () ((_ a b) (when a b)))))
      (when #f 1))))

;; erratum 13: let-syntax with zero definitions
(show 'zero-defs (let-syntax () 42))

;; ---- der1

;; case with =>  (erratum 6)
(show 'case=> (case 5 ((2 3 5 7) => (lambda (x) (* x 10))) (else 'no)))
(show 'case-else=> (case 'z ((a) 1) (else => (lambda (x) (list x 'else)))))
(show 'case-sym (case (* 2 3) ((2 3 5 7) 'prime) ((1 4 6 8 9) 'composite)))
(show 'case-char (case #\a ((#\a) 'a) (else 'other)))
(show 'case-str (case "a" (("a") 'eqv-string) (else 'not-eqv)))
(show 'case-empty-list (case '() ((()) 'nil) (else 'other)))
(show 'case-nomatch-unspec (list (if (eq? (case 1 ((2) 'x)) 'x) 'bad 'ok)))
;; case evaluates key once
(define cnt 0)
(case (begin (set! cnt (+ cnt 1)) 3) ((1) 'a) ((2) 'b) ((3) 'c))
(show 'case-once cnt)
;; case with => where clause after
(show 'case=>2 (case 3 ((1) => -) ((3) => -) (else 'x)))

;; when/unless values
(show 'when (when #t 1 2) (unless #f 3 4))
;; cond => with multiple values? cond with test only
(show 'cond-test (cond ((assv 2 '((1 . a) (2 . b))) => cdr) (else 'no)) (cond (5)) (cond (#f 1) ((+ 1 1))))
(show 'cond-else (cond (#f 1) (else 2 3)))

;; do with no step, body, result expression list
(show 'do (do ((vec (make-vector 5)) (i 0 (+ i 1))) ((= i 5) vec) (vector-set! vec i i)))
(show 'do2 (let ((x '(1 3 5 7 9))) (do ((x x (cdr x)) (sum 0 (+ sum (car x)))) ((null? x) sum))))
(show 'do-multi-result (do ((i 0 (+ i 1))) ((= i 2) 'a 'b)))
;; do captures fresh bindings per iteration
(show 'do-fresh (let ((procs '())) (do ((i 0 (+ i 1))) ((= i 3)) (set! procs (cons (lambda () i) procs))) (map (lambda (p) (p)) procs)))

;; case-lambda
(define range
  (case-lambda
    ((e) (range 0 e))
    ((b e) (do ((r '() (cons e r)) (e (- e 1) (- e 1))) ((< e b) r)))))
(show 'case-lambda (range 3) (range 3 5))
(define cl2 (case-lambda ((a) 'one) ((a b) 'two) ((a . rest) (list 'many rest)) (args 'zero)))
(show 'cl2 (cl2 1) (cl2 1 2) (cl2 1 2 3) (cl2))
(define cl3 (case-lambda (args (list 'all args))))
(show 'cl3 (cl3) (cl3 1 2))
(show 'cl-err (guard (e ((error-object? e) 'error-object) (#t (list 'raised e))) ((case-lambda ((a) a) ((a b) b)) 1 2 3)))

;; let*-values
(show 'let*-values (let ((a 'a) (b 'b) (x 'x) (y 'y)) (let*-values (((a b) (values x y)) ((x y) (values a b))) (list a b x y))))
(show 'let-values (let ((a 'a) (b 'b) (x 'x) (y 'y)) (let-values (((a b) (values x y)) ((x y) (values a b))) (list a b x y))))
(show 'let-values-rest (let-values (((a . r) (values 1 2 3)) (all (values 4 5))) (list a r all)))

;; internal define-values and define-record-type
(define (f)
  (define-values (q r) (floor/ 17 5))
  (define-values (x . y) (values 1 2 3))
  (define-values all (values 7 8))
  (define-record-type point (make-point x y) point? (x point-x set-point-x!) (y point-y))
  (define p (make-point q r))
  (set-point-x! p 10)
  (list q r x y all (point-x p) (point-y p) (point? p) (point? 5)))
(show 'internal (f))
(define-values (tq tr) (exact-integer-sqrt 17))
(show 'tl-define-values tq tr)
(define-values () (values))
(show 'define-values-empty)

;; parameterize with converter
(define radix (make-parameter 10 (lambda (x) (if (and (exact-integer? x) (<= 2 x 16)) x (error "invalid radix")))))
(define (f2 n) (number->string n (radix)))
(show 'param (f2 12) (parameterize ((radix 2)) (f2 12)) (f2 12))
(define conv-count 0)
(define p2 (make-parameter 1 (lambda (x) (set! conv-count (+ conv-count 1)) (* x 2))))
(show 'p2-init (p2) conv-count)
(parameterize ((p2 5)) (show 'p2-in (p2) conv-count))
(show 'p2-after (p2) conv-count)
(show 'param-err (guard (e (#t 'caught)) (parameterize ((radix 0)) (f2 12))))
(show 'param-after-err (radix))
;; parameterize: all exprs evaluated before conversion? order unspecified, values seen
(define pa (make-parameter 1))
(define pb (make-parameter 2))
(show 'param-parallel (parameterize ((pa (pb)) (pb (pa))) (list (pa) (pb))))
;; parameterize body is a body (definitions)
(show 'param-body (parameterize ((pa 3)) (define z (pa)) z))
;; parameterize with call/cc re-entry
(define k #f)
(define plog '())
(parameterize ((pa 'inside))
  (call/cc (lambda (c) (set! k c)))
  (set! plog (cons (pa) plog)))
(if (< (length plog) 2) (k #f))
(show 'param-reenter plog (pa))

;; guard
(show 'guard1 (guard (e (#t (list 'caught e))) (raise 'oops)))
(show 'guard=> (guard (e ((assq 'a e) => cdr) ((assq 'b e))) (raise (list (cons 'a 42)))))
(show 'guard=>2 (guard (e ((assq 'a e) => cdr) ((assq 'b e))) (raise (list (cons 'b 23)))))
;; reraise with raise-continuable in the dynamic env of the raise
(show 'guard-reraise
  (with-exception-handler
    (lambda (e) 42)
    (lambda () (+ 1 (guard (e ((string? e) 'string)) (raise-continuable 'sym))))))
(define pp (make-parameter 'outer))
(show 'guard-reraise-env
  (with-exception-handler
    (lambda (e) (pp))
    (lambda ()
      (parameterize ((pp 'guard-level))
        (guard (e ((string? e) 'string))
          (parameterize ((pp 'raise-level))
            (raise-continuable 'sym)))))))
;; guard body is a body
(show 'guard-body (guard (e (#t 'no)) (define x 5) x))
;; guard with else
(show 'guard-else (guard (e ((string? e) 's) (else 'else)) (raise 1)))
;; guard: clauses evaluated with guard's dynamic env
(show 'guard-clause-env (parameterize ((pp 'g)) (guard (e (#t (pp))) (parameterize ((pp 'inner)) (raise 1)))))
;; dynamic-wind in guard
(define dw '())
(guard (e (#t (set! dw (cons 'handler dw))))
  (dynamic-wind (lambda () (set! dw (cons 'before dw))) (lambda () (raise 'x)) (lambda () (set! dw (cons 'after dw)))))
(show 'guard-dw (reverse dw))
;; reraise into a dynamic-wind: before re-entered
(set! dw '())
(show 'guard-reraise-dw
  (with-exception-handler
    (lambda (e) (set! dw (cons 'handler dw)) 10)
    (lambda ()
      (guard (e (#f 'never))
        (dynamic-wind (lambda () (set! dw (cons 'before dw)))
                      (lambda () (+ 1 (raise-continuable 'x)))
                      (lambda () (set! dw (cons 'after dw))))))))
(show 'dw-log (reverse dw))

;; promises
(show 'promise (force (delay (+ 1 2))) (promise? (delay 1)) (promise? (make-promise 1)) (force (make-promise 7)))
(show 'make-promise-idem (let ((p (delay 1))) (eq? p (make-promise p))))
(define pcount 0)
(define p (delay (begin (set! pcount (+ pcount 1)) (if (> pcount x) pcount (force p)))))
(define x 5)
(define-syntax my-stream-cons (syntax-rules () ((_ a b) (cons a (delay b)))))
(define (stream-tail s k) (if (= k 0) s (stream-tail (force (cdr s)) (- k 1))))
(define (ints n) (my-stream-cons n (ints (+ n 1))))
(show 'stream (car (stream-tail (ints 0) 1000)))
;; R7RS example: loop via delay-force
(define (loop n) (delay-force (if (= n 0) (delay 'done) (loop (- n 1)))))
(show 'delay-force (force (loop 100000)))
(define r (delay (begin (set! count (+ count 1)) (if (> count x) count (force r)))))
(define count 0)
(set! x 5)
(show 'r7rs-example (force r))
(show 'delay-values (call-with-values (lambda () (force (delay (values 1)))) list))

;; quasiquote
(show 'qq `(list ,(+ 1 2) 4))
(show 'qq2 (let ((name 'a)) `(list ,name ',name)))
(show 'qq3 `(a ,(+ 1 2) ,@(map abs '(4 -5 6)) b))
(show 'qq4 `((foo ,(- 10 3)) ,@(cdr '(c)) . ,(car '(cons))))
(show 'qq5 `#(10 5 ,(abs 2) ,@(map abs '(16 9)) 8))
(show 'qq6 (let ((foo '(foo bar)) (@baz 'baz)) `(list ,@foo , @baz)))
(show 'qq-nest `(a `(b ,(c ,(+ 1 2)))))
(show 'qq-nest2 `(a `(b ,(a1 ,@(map abs '(-1 2))) ,(foo ,(+ 1 3) d) e) f))
(show 'qq-nest3 (let ((name1 'x) (name2 'y)) `(a `(b ,,name1 ,',name2 d) e)))
(show 'qq-long `(1 ,@'() . 2))
(show 'qq-equiv (equal? (quasiquote (list (unquote (+ 1 2)) 4)) '(list 3 4)))
(show 'qq-quasi-quote '(quasiquote (list (unquote (+ 1 2)) 4)))
(show 'qq-unquote-dot `(1 . ,(+ 1 1)))
(show 'qq-vector-nest `#(1 `#(2 ,(3 ,(+ 1 3)))))
(show 'qq-splice-end `(1 ,@(list 2 3)))
(show 'qq-splice-only `(,@(list 1 2)))
(show 'qq-fresh (let ((f (lambda () `(1 ,2)))) (eq? (f) (f))))
(show 'qq-unquote-splicing-vec `#(,@'()))
(show 'qq-dotted-unquote `(1 unquote (+ 1 1)))

;; ---- der2
(show 'shadow=> (let ((=> #f)) (cond (#t => 'ok))))
(show 'let*-dup (let* ((x 1) (x (+ x 1))) x))
;; erratum 9
(define (means ton)
  (letrec*
     ((mean (lambda (f g) (f (/ (sum g ton) n))))
      (sum (lambda (g ton)
          (if (null? ton) (+)
            (if (number? ton) (g ton)
                (+ (sum g (car ton)) (sum g (cdr ton)))))))
      (n (sum (lambda (x) 1) ton)))
    (values (mean values values) (mean exp log) (mean / /))))
(call-with-values (lambda () (means '(3 (1 4)))) (lambda (a b c) (show 'means a (< 2.28 b 2.29) c)))
(begin)
(show 'empty-begin-ok)
(begin (define bx 1) (define by 2))
(show 'begin-defs bx by)
(define x 1)
(define x 2)
(show 'redefine x)
(define (f) 1)
(set! f (lambda () 2))
(show 'set-proc (f))
;; R7RS 4.3.2 example
(define-syntax be-like-begin
  (syntax-rules ()
    ((be-like-begin name)
     (define-syntax name
       (syntax-rules ()
         ((name expr (... ...))
          (begin expr (... ...))))))))
(be-like-begin sequence)
(show 'be-like-begin (sequence 1 2 3 4))
;; 4.3.1 examples
(show 'let-syntax-ex
  (let-syntax ((given-that (syntax-rules () ((_ test stmt1 stmt2 ...) (if test (begin stmt1 stmt2 ...))))))
    (let ((if #t))
      (given-that if (set! if 'now))
      if)))
(show 'let-syntax-ex2
  (let ((x 'outer))
    (let-syntax ((m (syntax-rules () ((m) x))))
      (let ((x 'inner))
        (m)))))
(show 'letrec-syntax-ex
  (letrec-syntax
    ((my-or (syntax-rules ()
              ((my-or) #f)
              ((my-or e) e)
              ((my-or e1 e2 ...)
               (let ((temp e1))
                 (if temp temp (my-or e2 ...)))))))
    (let ((x #f) (y 7) (temp 8) (let odd?) (if even?))
      (my-or x (let temp) (if y) y))))
;; 4.2.2 letrec example
(show 'letrec-ex (letrec ((even? (lambda (n) (if (zero? n) #t (odd? (- n 1))))) (odd? (lambda (n) (if (zero? n) #f (even? (- n 1)))))) (even? 88)))
;; 4.2.4 do example
(show 'do-ex (do ((vec (make-vector 5)) (i 0 (+ i 1))) ((= i 5) vec) (vector-set! vec i i)))
;; named let
(show 'named-let (let loop ((numbers '(3 -2 1 6 -5)) (nonneg '()) (neg '()))
  (cond ((null? numbers) (list nonneg neg))
        ((>= (car numbers) 0) (loop (cdr numbers) (cons (car numbers) nonneg) neg))
        ((< (car numbers) 0) (loop (cdr numbers) nonneg (cons (car numbers) neg))))))
;; named let: name not visible in inits
(show 'named-let-scope (let ((loop 'outer)) (let loop ((x loop)) x)))
;; body: define-syntax used before its definition in the same body? (R7RS 5.3: the meaning)
(show 'body-order (let () (define (g) (mac)) (define-syntax mac (syntax-rules () ((_) 'mac))) (g)))
;; define-values at toplevel with rest
(define-values (dv1 . dvr) (values 1 2 3))
(show 'dv dv1 dvr)
;; string, char case
(show 'case-strs (case (string->symbol "b") ((a) 1) ((b c) 2) (else 3)))
;; when/unless with false/true test: unspecified but not error
(when #f 1)
(unless #t 1)
(show 'when-unless-ok)
;; nested quasiquote with unquote-splicing at depth
(show 'qq-ex1 `(1 `,(+ 1 ,(+ 2 3)) 4))
;; vector qq with splice at the end
(show 'qq-vec `#(a ,@(list 1 2)))
;; case with list datums compares by eqv
(show 'case-list (case (list 1) (((1)) 'eqv) (else 'no)))
;; 4.2.6 parameterize body tail position and values
(define pp (make-parameter 1))
(show 'param-values (call-with-values (lambda () (parameterize ((pp 2)) (values (pp) 3))) list))
;; guard returning multiple values
(show 'guard-values (call-with-values (lambda () (guard (e (#t (values 1 2))) (raise 'x))) list))
(show 'guard-values2 (call-with-values (lambda () (guard (e (#t 0)) (values 3 4))) list))
;; guard => with no clauses matching and an outer guard
(show 'guard-nested (guard (e ((symbol? e) (list 'outer e))) (guard (e ((string? e) 'inner)) (raise 'sym))))
;; raise non-continuable handler returns => secondary exception
(show 'raise-return (guard (e (#t 'secondary)) (with-exception-handler (lambda (e) 'ret) (lambda () (raise 'x)))))

;; ---- hyg2
;; a macro that defines a helper macro and a procedure using it, at top level, twice
(define-syntax def-getter
  (syntax-rules ()
    ((_ name val)
     (begin
       (define-syntax helper (syntax-rules () ((_) val)))
       (define (name) (helper))))))
(def-getter get-a 'a)
(def-getter get-b 'b)
(show 'introduced-define-syntax (get-a) (get-b))
;; the same in a body
(show 'body (let () (def-getter g1 1) (def-getter g2 2) (list (g1) (g2))))
;; macro-defining macro whose inner macro has a literal from the outer use
(define-syntax def-matcher
  (syntax-rules ()
    ((_ name lit)
     (define-syntax name (syntax-rules (lit) ((_ lit) 'matched) ((_ x) 'other))))))
(def-matcher m1 foo)
(show 'inner-literal (m1 foo) (m1 bar))
;; named let with no bindings
(show 'named-let0 (let loop () 'x))
;; qq: unquote-splicing inside a vector at depth 1
(show 'qq-vec-depth `(1 `#(,@(list ,@(list 2 3)))))
;; macro expanding into a definition using a template-introduced name passed in from use
(define-syntax def-two
  (syntax-rules () ((_ a b) (begin (define a 1) (define b (+ a 1))))))
(def-two p q)
(show 'def-two p q)
;; shadowing a macro with a local procedure of the same name inside a body definition
(define-syntax foo (syntax-rules () ((_) 'macro)))
(show 'shadow-body (let () (define (foo) 'proc) (foo)))
;; let-syntax inside a lambda body with definitions after
(show 'ls-defs ((lambda () (let-syntax ((m (syntax-rules () ((_) 5)))) (define v (m)) v))))
;; do with body that uses a macro introducing a binding
(show 'do-macro (do ((i 0 (+ i 1)) (acc '() (cons i acc))) ((= i 3) acc)))

;; ---- import sets: literals match by binding through prefix and rename
(show 'prefix-else (b:cond (#f 1) (b:else 2)))
(show 'renamed-else (cond (#f 1) (otherwise 3)))
(show 'renamed-arrow (cond ((assv 1 '((1 . 4))) arrow cdr) (otherwise 3)))
(show 'renamed-case (case 5 ((1) 1) (otherwise arrow (lambda (x) (* x 2)))))
