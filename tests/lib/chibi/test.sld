;; A portable (chibi test) shim for the vendored R7RS suite (tests/r7rs/).
;;
;; It provides the subset of chibi-scheme's (chibi test) that the suite uses,
;; with chibi's comparison semantics (test-equal? below is chibi's), and reports
;; every test as one machine-readable line on the standard output port as it
;; was when this library was loaded:
;;
;;   @@r7rs-test BEGIN <group>
;;   @@r7rs-test PASS <label>
;;   @@r7rs-test FAIL <label> :: expected <x> got <y>
;;   @@r7rs-test ERROR <label> :: <condition>
;;   @@r7rs-test END <group>
;;
;; The conformance tests (tests/harness) count these lines. The same shim runs
;; under chibi-scheme (`chibi-scheme -I tests/lib ...`) to get each section's
;; total number of tests.
;;
;; approx-equal? and test-equal? are copied from chibi-scheme's lib/chibi/test.scm,
;; Copyright (c) 2009-2021 Alex Shinn, BSD-3-Clause (see tests/r7rs/COPYING).

(define-library (chibi test)
  (export test test-equal test-assert test-not test-values test-error
          test-begin test-end test-equal? current-test-epsilon)
  (import (scheme base) (scheme write) (scheme complex))
  (begin

    (define report-port (current-output-port))

    (define current-test-epsilon (make-parameter 1e-5))

    ;; chibi's approx-equal? and test-equal?, unchanged.
    (define (approx-equal? a b epsilon)
      (cond
       ((> (abs a) (abs b))
        (approx-equal? b a epsilon))
       ((zero? a)
        (< (abs b) epsilon))
       (else
        (< (abs (/ (- a b) b)) epsilon))))

    (define (test-equal? expect res)
      (or (equal? expect res)
          (if (real? expect)
              (and (inexact? expect)
                   (real? res)
                   (approx-equal? expect res (current-test-epsilon)))
              (and (complex? res)
                   (complex? expect)
                   (test-equal? (real-part expect) (real-part res))
                   (test-equal? (imag-part expect) (imag-part res))))))

    (define (->string x)
      (let ((out (open-output-string)))
        (write x out)
        (get-output-string out)))

    (define (condition->string e)
      (if (error-object? e)
          (->string (cons (error-object-message e) (error-object-irritants e)))
          (->string e)))

    (define (report status label . detail)
      (write-string "@@r7rs-test " report-port)
      (write-string status report-port)
      (write-string " " report-port)
      (write-string label report-port)
      (if (pair? detail)
          (begin (write-string " :: " report-port)
                 (write-string (car detail) report-port)))
      (newline report-port)
      (flush-output-port report-port))

    (define (label-of name quoted)
      (if name
          (if (string? name) name (->string name))
          (->string quoted)))

    (define (run-test name quoted expect-thunk thunk compare)
      (let ((label (label-of name quoted)))
        (guard (e (#t (report "ERROR" label (condition->string e))))
          (let* ((expected (expect-thunk))
                 (res (thunk)))
            (if (compare expected res)
                (report "PASS" label)
                (report "FAIL" label
                        (string-append "expected " (->string expected)
                                       " got " (->string res))))))))

    (define-syntax test
      (syntax-rules ()
        ((_ expect expr)
         (run-test #f 'expr (lambda () expect) (lambda () expr) test-equal?))
        ((_ name expect expr)
         (run-test name 'expr (lambda () expect) (lambda () expr) test-equal?))))

    (define-syntax test-equal
      (syntax-rules ()
        ((_ equal expect expr)
         (run-test #f 'expr (lambda () expect) (lambda () expr) equal))
        ((_ equal name expect expr)
         (run-test name 'expr (lambda () expect) (lambda () expr) equal))))

    (define-syntax test-assert
      (syntax-rules ()
        ((_ expr)
         (run-test #f 'expr (lambda () #t) (lambda () (if expr #t #f)) eq?))
        ((_ name expr)
         (run-test name 'expr (lambda () #t) (lambda () (if expr #t #f)) eq?))))

    (define-syntax test-not
      (syntax-rules ()
        ((_ expr) (test-assert (not expr)))
        ((_ name expr) (test-assert name (not expr)))))

    (define-syntax test-values
      (syntax-rules ()
        ((_ expect expr)
         (test-values #f expect expr))
        ((_ name expect expr)
         (run-test name 'expr
                   (lambda () (call-with-values (lambda () expect) list))
                   (lambda () (call-with-values (lambda () expr) list))
                   test-equal?))))

    (define (run-test-error name quoted pred thunk)
      (let ((label (label-of name quoted)))
        (call-with-current-continuation
         (lambda (k)
           (with-exception-handler
            (lambda (e)
              (k (if (or (not pred) (pred e))
                     (report "PASS" label)
                     (report "FAIL" label
                             (string-append "raised " (condition->string e)
                                            " which fails the predicate")))))
            (lambda ()
              (let ((res (thunk)))
                (report "FAIL" label
                        (string-append "expected an error, got " (->string res))))))))))

    (define-syntax test-error
      (syntax-rules ()
        ((_ expr)
         (run-test-error #f 'expr #f (lambda () expr)))
        ((_ name expr)
         (run-test-error name 'expr #f (lambda () expr)))
        ((_ name pred expr)
         (run-test-error name 'expr pred (lambda () expr)))))

    (define groups '())

    (define (test-begin . o)
      (let ((name (if (pair? o) (car o) "")))
        (set! groups (cons name groups))
        (report "BEGIN" name)))

    (define (test-end . o)
      (if (pair? groups)
          (let ((name (car groups)))
            (set! groups (cdr groups))
            (report "END" name))))))
