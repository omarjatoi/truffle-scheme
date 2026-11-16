;; Records (R7RS §5.5, design.md D1): define-record-type on SRecord and SRecordType. Each evaluation
;; makes a new type, and its constructor, predicate, accessors and modifiers are primitives made for
;; it (plan.md M7-04), named by their bindings: call sites run them in place, with the type and the
;; field as constants.

(define-syntax define-record-type
  (syntax-rules ()
    ((_ type (constructor cfield ...) predicate (fname accessor . modifier) ...)
     (begin
       (define type (%make-record-type 'type '(fname ...)))
       (define constructor (%record-constructor type '(cfield ...) 'constructor))
       (define predicate (%record-predicate type 'predicate))
       (%define-record-field type fname accessor . modifier) ...))))

(define-syntax %define-record-field
  (syntax-rules ()
    ((_ type fname accessor)
     (define accessor (%record-accessor type 'fname 'accessor)))
    ((_ type fname accessor modifier)
     (begin
       (%define-record-field type fname accessor)
       (define modifier (%record-modifier type 'fname 'modifier))))))
