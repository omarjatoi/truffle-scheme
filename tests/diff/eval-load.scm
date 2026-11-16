(import (scheme base) (scheme write) (scheme eval) (scheme file) (scheme load) (scheme repl))

;; load (R7RS §6.13.1) and syntax-error (§4.3.3). The program writes the file it loads, in the
;; current directory, and deletes it at the end.
(define file "eval-load-diff.tmp.scm")

(define (write-file forms)
  (with-output-to-file file
    (lambda ()
      (for-each (lambda (form) (write form) (newline)) forms))))

;; Into the interaction environment (the default): its definitions and macros stay there.
(write-file '((define loaded-x 42)
              (define-syntax twice (syntax-rules () ((_ e) (begin e e))))
              (define (loaded-square n) (* n n))
              (display "loading")
              (newline)))
(load file)
(write (eval '(list loaded-x (loaded-square 5)) (interaction-environment)))
(newline)
(eval '(twice (display "twice ")) (interaction-environment))
(newline)
(load file (interaction-environment))

;; Into an environment made by environment: only its bindings are visible.
(write-file '((write (map (lambda (x) (* x x)) '(1 2 3)))
              (newline)
              (write (let loop ((i 0) (acc '())) (if (= i 3) (reverse acc) (loop (+ i 1) (cons i acc)))))
              (newline)))
(load file (environment '(scheme base) '(scheme write)))

;; A missing file is a file error.
(write (guard (e ((file-error? e) 'file-error)) (load "eval-load-diff-missing.scm")))
(newline)

;; syntax-error: R7RS §4.3.3's example. Valid uses expand as usual (eval-syntax-error.scm has an
;; invalid one). Not compared here: a syntax-error caught inside eval, after which chibi-scheme
;; prints a stray #<undef> when the program ends (SyntaxErrorLoadTest covers it).
(define-syntax simple-let
  (syntax-rules ()
    ((_ (head ... ((x . y) val) . tail)
        body1 body2 ...)
     (syntax-error
      "expected an identifier but got"
      (x . y)))
    ((_ ((name val) ...) body1 body2 ...)
     ((lambda (name ...) body1 body2 ...)
      val ...))))

(write (simple-let ((a 1) (b 2)) (+ a b)))
(newline)
(delete-file file)
