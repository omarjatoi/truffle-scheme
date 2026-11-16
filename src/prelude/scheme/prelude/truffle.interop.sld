;; EXPLORATION (docs/interop-and-tools-exploration.md): Java and other Truffle languages from
;; Scheme, through Truffle's InteropLibrary. Not an R7RS library; design.md D10 leaves host access
;; out of v1. The primitives are scheme.interop.InteropPrimitives.
(define-library (truffle interop)
  (import (r7rs %primitives))
  (export
   java-type java-new java-invoke java-field java-set-field!
   foreign? foreign-null? foreign-execute foreign-length foreign-ref foreign->vector
   polyglot-import polyglot-export polyglot-eval))
