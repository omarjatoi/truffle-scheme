(import (scheme base) (scheme write))

;; A generator over the leaves of a tree, from two continuations.
(define (make-generator tree)
  (define return #f)
  (define resume #f)
  (define (walk t)
    (if (pair? t)
        (begin (walk (car t)) (walk (cdr t)))
        (if (null? t)
            #f
            (call/cc (lambda (r) (set! resume r) (return t))))))
  (lambda ()
    (call/cc (lambda (ret)
               (set! return ret)
               (if resume
                   (resume #f)
                   (begin (walk tree) (return 'done)))))))

(define (drain g)
  (define x (g))
  (if (eq? x 'done) '(done) (cons x (drain g))))

(display (drain (make-generator '((a (b)) c ((d e) . f)))))
(newline)

;; Two generators interleaved.
(define (same-fringe? a b)
  (define ga (make-generator a))
  (define gb (make-generator b))
  (define (loop)
    (define x (ga))
    (define y (gb))
    (if (eq? x y)
        (if (eq? x 'done) #t (loop))
        #f))
  (loop))

(display (same-fringe? '(a (b c) ((d)) e) '((a b) (c (d e)))))
(newline)
(display (same-fringe? '(a b c) '(a (c b))))
(newline)
