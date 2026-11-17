(import (scheme base) (scheme write))

;; A 1,000,000-deep non-tail recursion through `apply`. In the native launcher, this overflowed the
;; stack before the spill: while the recursion runs compiled at Truffle's first tier, each level is
;; a real call, whose frames the call-cost estimate undercounted (design.md "Stack-depth spill").
;; `//tests:space` runs it under -Xss256k, and `//tests:space_native` through the native launcher
;; on stacks from 256 KB to 256 MB.
(define (applied i n)
  (if (= i n)
      0
      (+ 1 (apply applied (list (+ i 1) n)))))

(display (applied 0 1000000))
(newline)
