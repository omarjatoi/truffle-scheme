;; The characters, strings and symbols area (R7RS §6.5–6.7, AGENTS.md "Areas"): procedures written in Scheme
;; (design.md D5). Everything else in the area is a primitive (CharStringPrimitives.java).

;; The length of the shortest of strings.
(define (%min-string-length strings)
  (let loop ((rest (cdr strings)) (n (string-length (car strings))))
    (if (pair? rest)
        (let ((m (string-length (car rest))))
          (loop (cdr rest) (if (< m n) m n)))
        n)))

;; The characters at index i of each of strings.
(define (%string-refs strings i)
  (if (pair? strings) (cons (string-ref (car strings) i) (%string-refs (cdr strings) i)) '()))

;; string-map and string-for-each call their procedure in a non-tail position, so they live here
;; (D5). string-map accumulates a fresh list and builds the string at the end, so re-entering a
;; continuation captured in the procedure never changes an earlier result.
(define (string-map f s . ss)
  (if (null? ss)
      (let ((n (string-length s)))
        (let loop ((i 0) (acc '()))
          (if (< i n)
              (loop (+ i 1) (cons (f (string-ref s i)) acc))
              (list->string (reverse acc)))))
      (let* ((strings (cons s ss)) (n (%min-string-length strings)))
        (let loop ((i 0) (acc '()))
          (if (< i n)
              (loop (+ i 1) (cons (apply f (%string-refs strings i)) acc))
              (list->string (reverse acc)))))))

(define (string-for-each f s . ss)
  (if (null? ss)
      (let ((n (string-length s)))
        (let loop ((i 0))
          (if (< i n)
              (begin (f (string-ref s i)) (loop (+ i 1))))))
      (let* ((strings (cons s ss)) (n (%min-string-length strings)))
        (let loop ((i 0))
          (if (< i n)
              (begin (apply f (%string-refs strings i)) (loop (+ i 1))))))))
