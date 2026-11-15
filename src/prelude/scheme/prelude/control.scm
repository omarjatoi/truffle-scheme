;; dynamic-wind and the single rewind (design.md "Dynamic environment", D5). Core forms only until
;; the expander (M3).
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
