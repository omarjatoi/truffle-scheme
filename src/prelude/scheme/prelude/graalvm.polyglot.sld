;; Other GraalVM languages, the polyglot bindings, foreign values and Java classes (design.md D20).
;; Not a standard library: a program sees these only if it imports (graalvm polyglot). The
;; procedures are primitives (PolyglotPrimitives.java) under %-names, which the interaction
;; environment doesn't list, renamed here.
(define-library (graalvm polyglot)
  (import (r7rs %primitives))
  (export
   (rename %polyglot-eval polyglot-eval)
   (rename %polyglot-eval-file polyglot-eval-file)
   (rename %polyglot-language-available? polyglot-language-available?)
   (rename %polyglot-import polyglot-import)
   (rename %polyglot-export polyglot-export)
   (rename %java-type java-type)
   (rename %foreign? foreign?)
   (rename %foreign-null? foreign-null?)
   (rename %foreign-ref foreign-ref)
   (rename %foreign-set! foreign-set!)
   (rename %foreign-length foreign-length)
   (rename %foreign-keys foreign-keys)
   (rename %foreign->list foreign->list)
   (rename %foreign->vector foreign->vector)
   (rename %foreign-invoke foreign-invoke)
   (rename %foreign-new foreign-new)
   (rename %foreign-instance? foreign-instance?)))
