;; Records (R7RS §5.5, design.md D1): define-record-type on SRecord and SRecordType. Each evaluation
;; makes a new type, and its constructor, predicate, accessors and modifiers are primitives made for
;; it (plan.md M7-04), named by their bindings: call sites run them in place, with the type and the
;; field as constants.

;;
;; R7RS §5.5: "It is an error for the same identifier to occur more than once as a field name. It is
;; also an error for the same identifier to occur more than once as an accessor or mutator name."
;; The type's definition holds two lambdas that are never called, with the field names and with the
;; accessor and modifier names as their formals: a duplicate is a syntax error when they expand, at
;; the top level too, where a second definition would otherwise be a redefinition.
(define-syntax define-record-type
  (syntax-rules ()
    ((_ type (constructor cfield ...) predicate (fname accessor modifier ...) ...)
     (begin
       (define type
         (begin
           (if #f (lambda (fname ...) (lambda (accessor ... modifier ... ...) #f)))
           (%make-record-type 'type '(fname ...))))
       (define constructor (%record-constructor type '(cfield ...) 'constructor))
       (define predicate (%record-predicate type 'predicate))
       (%define-record-field type fname accessor modifier ...) ...))))

(define-syntax %define-record-field
  (syntax-rules ()
    ((_ type fname accessor)
     (define accessor (%record-accessor type 'fname 'accessor)))
    ((_ type fname accessor modifier)
     (begin
       (%define-record-field type fname accessor)
       (define modifier (%record-modifier type 'fname 'modifier))))))
