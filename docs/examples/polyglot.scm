(import (scheme base) (scheme write) (graalvm polyglot))

(define (show . items)
  (for-each (lambda (x) (write x) (display " ")) items)
  (newline))

;; Only Scheme is on the launcher's module path.
(show (polyglot-language-available? "scheme") (polyglot-language-available? "js"))

;; A Java class: its constructor, its methods, and what comes back.
(guard (e ((error-object? e) (show 'no-java (error-object-message e))))
  (let ((list (foreign-new (java-type "java.util.ArrayList"))))
    (foreign-invoke list 'add 1)
    (foreign-invoke list 'add "two")
    (show (foreign? list) (foreign-length list) (foreign->list list))
    (show (foreign-invoke (java-type "java.lang.Math") 'max 3 7))))
