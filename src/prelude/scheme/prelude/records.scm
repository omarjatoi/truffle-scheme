;; Records (R7RS §5.5, design.md D1): define-record-type on SRecord and SRecordType. Field indices
;; are looked up once, when the record type is defined.

(define-syntax define-record-type
  (syntax-rules ()
    ((_ type (constructor cfield ...) predicate (fname accessor . modifier) ...)
     (begin
       (define type (%make-record-type 'type '(fname ...)))
       (define constructor
         (let ((indices (vector (%record-index type 'cfield) ...)))
           (lambda (cfield ...) (%make-record type indices cfield ...))))
       (define predicate (lambda (x) (%record? type x)))
       (%define-record-field type fname accessor . modifier) ...))))

(define-syntax %define-record-field
  (syntax-rules ()
    ((_ type fname accessor)
     (define accessor
       (let ((i (%record-index type 'fname)))
         (lambda (record) (%record-ref type i record)))))
    ((_ type fname accessor modifier)
     (begin
       (%define-record-field type fname accessor)
       (define modifier
         (let ((i (%record-index type 'fname)))
           (lambda (record value) (%record-set! type i record value))))))))
