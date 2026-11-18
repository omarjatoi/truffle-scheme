;; The foreign-call benchmark (bench/README.md "Polyglot", design.md D20): Scheme calling a
;; function of GraalVM's SimpleLanguage (SL) in a loop, from a call site and from a trampoline,
;; reading the elements of a Java array and the fields of a Java object, and SL calling a Scheme
;; procedure back in a loop of its own.
;;
;;   foreign.scm ITERATIONS COUNT [CASE ...]
;;
;; Runs each case (all of them by default) COUNT times an iteration, ITERATIONS times, and prints a
;; line per case and iteration: `<case> <iteration> <milliseconds>`.

(import (scheme base) (scheme write) (scheme time) (scheme process-context) (graalvm polyglot))

(define sl
  (polyglot-eval "sl" "
function add(a, b) { return a + b; }
function callBack(f, n) { i = 0; s = 0; while (i < n) { s = s + f(i); i = i + 1; } return s; }
function main() { o = new(); o.add = add; o.callBack = callBack; return o; }"))
(define add (foreign-ref sl 'add))
(define call-back (foreign-ref sl 'callBack))

;; A Java int[] of 0 to 999, and a Java object with public fields (StreamTokenizer's ttype is -4,
;; TT_NOTHING, before it reads anything).
(define array (foreign-invoke (foreign-invoke (java-type "java.util.stream.IntStream") 'range 0 1000) 'toArray))
(define tokenizer
  (foreign-new (java-type "java.io.StreamTokenizer") (foreign-new (java-type "java.io.StringReader") "")))

;; A non-tail call of the SL function, at a call site.
(define (calls n)
  (let loop ((i 0) (s 0))
    (if (= i n) s (loop (+ i 1) (add s 1)))))

;; A tail call of it, which the trampoline of the call site of add-one makes.
(define (add-one x) (add x 1))
(define (tail-calls n)
  (let loop ((i 0) (s 0))
    (if (= i n) s (loop (+ i 1) (add-one s)))))

;; foreign-ref of a Java array's elements.
(define (elements n)
  (let loop ((i 0) (s 0))
    (if (= i n) s (loop (+ i 1) (+ s (foreign-ref array (remainder i 1000)))))))

;; foreign-ref of a Java object's field.
(define (members n)
  (let loop ((i 0) (s 0))
    (if (= i n) s (loop (+ i 1) (+ s (foreign-ref tokenizer 'ttype))))))

;; SL calls a Scheme procedure n times.
(define (callbacks n)
  (call-back (lambda (i) (+ i 1)) n))

(define cases
  (list (list "calls" calls (lambda (n) n))
        (list "tail-calls" tail-calls (lambda (n) n))
        (list "elements" elements (lambda (n) (+ (* (quotient n 1000) 499500)
                                                 (let ((r (remainder n 1000))) (quotient (* r (- r 1)) 2)))))
        (list "members" members (lambda (n) (* -4 n)))
        (list "callbacks" callbacks (lambda (n) (quotient (* n (+ n 1)) 2)))))

(define arguments (cdr (command-line)))
(define iterations (string->number (car arguments)))
(define count (string->number (cadr arguments)))
(define chosen
  (if (null? (cddr arguments))
      cases
      (map (lambda (name) (assoc name cases)) (cddr arguments))))

(do ((iteration 1 (+ iteration 1)))
    ((> iteration iterations))
  (for-each
   (lambda (c)
     (let* ((start (current-jiffy))
            (result ((cadr c) count))
            (end (current-jiffy)))
       (unless (= result ((car (cddr c)) count))
         (error "wrong result" (car c) result))
       (display (car c))
       (display " ")
       (display iteration)
       (display " ")
       (display (/ (* 1000.0 (- end start)) (jiffies-per-second)))
       (newline)))
   chosen))
