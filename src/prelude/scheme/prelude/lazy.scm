;; Promises (R7RS §4.2.5, §7.3, design.md D1, D9): the procedures behind delay and delay-force (in
;; syntax.scm). A promise is an SPromise, a type of its own, with §7.3's state: done and its value,
;; or not done and the thunk of a delay-force. promise?, %make-promise and the state's accessors
;; are primitives (ControlPrimitives.java); force calls the thunk, so it is here (AGENTS.md rule 1).

(define (make-promise obj) (if (promise? obj) obj (%make-promise #t obj)))

;; §7.3's force. A promise forced again while its thunk runs keeps the value of the forcing that
;; completes first (the done? check after the call), and %promise-update! makes the promise the
;; thunk returned share this one's state, so a delay-force chain forces in a loop, in constant
;; space.
(define (force promise)
  (if (promise? promise)
      (if (%promise-done? promise)
          (%promise-value promise)
          (let ((promise* ((%promise-value promise))))
            (unless (%promise-done? promise)
              (%promise-update! promise* promise))
            (force promise)))
      promise))
