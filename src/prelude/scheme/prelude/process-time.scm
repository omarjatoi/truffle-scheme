;; The process context and time area (R7RS §6.14, AGENTS.md "Areas"): procedures written in Scheme
;; (design.md D5). The rest of the area is primitives (ProcessTimePrimitives.java).

;; exit runs every outstanding after-thunk, innermost first, through the single %rewind (control.scm,
;; design.md D9) from the current dynamic environment out to one with no winders, then exits the
;; context through Truffle (emergency-exit, which skips that step).
(define (exit . status)
  (let ((env (%current-dynamic-env)))
    (%rewind env (%make-dynamic-env '() (%dynamic-env-parameterization env) (%dynamic-env-handlers env))))
  (emergency-exit (if (null? status) #t (car status))))
