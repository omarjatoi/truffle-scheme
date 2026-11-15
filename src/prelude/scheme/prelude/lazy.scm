;; Promises (R7RS §4.2.5, §7.3, design.md D9): the procedures behind delay and delay-force (in
;; syntax.scm).

(define %promise-tag (cons 'promise '()))

(define (%make-promise done? value) (cons %promise-tag (cons done? value)))

(define (promise? x) (and (pair? x) (eq? (car x) %promise-tag)))

(define (make-promise obj) (if (promise? obj) obj (%make-promise #t obj)))

(define (force promise)
  (if (promise? promise)
      (let ((box (cdr promise)))
        (if (car box)
            (cdr box)
            (let ((promise* ((cdr box))))
              (unless (car (cdr promise))
                (let ((new (cdr promise*)))
                  (set-car! (cdr promise) (car new))
                  (set-cdr! (cdr promise) (cdr new))
                  (set-cdr! promise* (cdr promise))))
              (force promise))))
      promise))
