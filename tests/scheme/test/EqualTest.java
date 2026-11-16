package scheme.test;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.junit.Test;

/** M3-08 and M5-07: equal? (R7RS §6.1, design.md D9): structural, iterative, and terminating on cycles. */
public class EqualTest {

    private static String write(String expr) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("scheme").out(out).build()) {
            context.eval("scheme", "(write " + expr + ")");
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void structural() {
        assertEquals("(#t #t #t #t #f #f)", write(
                        "(list (equal? 'a 'a) (equal? '(a (b) c) '(a (b) c)) (equal? \"abc\" \"abc\") (equal? (vector 1 2) #(1 2)) (equal? 2 2.0) (equal? '(1 2) '(1 2 3)))"));
        assertEquals("(#t #f)", write("(list (equal? 1/2 (/ 2 4)) (equal? (make-vector 2 'a) (make-vector 3 'a)))"));
    }

    @Test
    public void circular() {
        assertEquals("(#t #f)", write("(let ((a (list 1 2)) (b (list 1 2)) (c (list 1 3))) (set-cdr! (cdr a) a) (set-cdr! (cdr b) b) (set-cdr! (cdr c) c) (list (equal? a b) (equal? a c)))"));
    }

    @Test
    public void deepDataDoesNotRecurse() {
        assertEquals("#t", write("(let loop ((i 0) (a '()) (b '())) (if (= i 1000000) (equal? a b) (loop (+ i 1) (list a) (list b))))"));
    }

    /*
     * M5-07: Adams and Dybvig's interleaved walk. Most of these cases are big enough to run past the
     * fast phase into union-find.
     */

    @Test
    public void mixedStructure() {
        assertEquals("(#t #f #f #t #f #t #f)", write("(list (equal? (list 1 \"ab\" (vector 'x #u8(1 2) '(y . z)) #\\c 2.5) (list 1 \"ab\" (vector 'x #u8(1 2) '(y . z)) #\\c 2.5))" +
                        " (equal? (vector 1 \"ab\") (vector 1 \"ac\")) (equal? #u8(1 2) #u8(1 3)) (equal? \"\\x3bb;x\" (string #\\x3bb #\\x)) (equal? \"a\" 'a)" +
                        " (equal? (vector) (vector)) (equal? '(1 . 2) '#(1 2)))"));
        assertEquals("(#t #f #f #t #t)", write("(list (equal? (string #\\a #\\b) (let ((s (string #\\x3bb #\\b))) (string-set! s 0 #\\a) s)) (equal? \"ab\" \"abc\")" +
                        " (equal? 2 2.0) (equal? 100000000000000000000 (* 10000000000 10000000000)) (equal? +nan.0 +nan.0))"));
    }

    @Test
    public void largeTreesPastTheFastBudget() {
        String tree = "(define (tree d leaf) (if (= d 0) (list leaf \"s\" #u8(1)) (cons (tree (- d 1) leaf) (tree (- d 1) leaf))))";
        assertEquals("(#t #f)", write("(let () " + tree + " (list (equal? (tree 14 1) (tree 14 1))" +
                        " (let ((a (tree 14 1)) (b (tree 14 1))) (set-car! (cdr (cdr (car (cdr b)))) 2) (equal? a b))))"));
        assertEquals("(#t #f)", write("(let ((a (make-list 100000 'x)) (b (make-list 100000 'x)) (c (make-list 100000 'x)))" +
                        " (set-car! (list-tail c 99999) 'y) (list (equal? a b) (equal? a c)))"));
    }

    @Test
    public void sharedDags() {
        // 3^60 paths through 60 nodes: equal? must not walk the unfolding.
        String dag = "(define (dag d) (if (= d 0) 'leaf (let ((x (dag (- d 1)))) (cons x (vector x x)))))";
        assertEquals("(#t #f)", write("(let () " + dag + " (list (equal? (dag 60) (dag 60)) (equal? (dag 60) (cons (dag 59) (vector (dag 59) (dag 58))))))"));
    }

    @Test
    public void circularOfDifferentShapes() {
        String circ = "(define (circ l) (let loop ((p l)) (if (null? (cdr p)) (begin (set-cdr! p l) l) (loop (cdr p)))))";
        // Cycles of lengths 1000 and 1001 holding the same element unfold to the same infinite list.
        assertEquals("(#t #f #f #t)", write("(let () " + circ + " (list (equal? (circ (make-list 1000 7)) (circ (make-list 1001 7)))" +
                        " (equal? (circ (list 1 2 3)) (circ (list 1 2 3 1 2 4))) (equal? (circ (make-list 5000 7)) (make-list 100000 7))" +
                        " (equal? (circ (list 1 2)) (cons 1 (cons 2 (circ (list 1 2)))))))"));
        // Cycles through cars and through vectors.
        assertEquals("(#t #f #t)", write("(let ((a (list #f 1)) (b (list #f 1)) (c (list #f 2)) (v (vector 1 #f)) (w (vector 1 #f)))" +
                        " (set-car! a a) (set-car! b b) (set-car! c c) (vector-set! v 1 (list v)) (vector-set! w 1 (list w))" +
                        " (list (equal? a b) (equal? a c) (equal? v w)))"));
    }

    @Test
    public void recordsCompareByIdentity() {
        assertEquals("(#t #f #f)", write("(let () (define-record-type point (make-point x y) point? (x point-x) (y point-y)) (let ((p (make-point 1 2)))" +
                        " (list (equal? p p) (equal? p (make-point 1 2)) (equal? (list (make-point 1 2)) (list (make-point 1 2))))))"));
    }

    @Test
    public void deepVectorsAndCars() {
        assertEquals("(#t #f)", write("(let loop ((i 0) (a \"x\") (b \"x\") (c \"y\")) (if (= i 300000) (list (equal? a b) (equal? a c))" +
                        " (loop (+ i 1) (vector 1 (list a)) (vector 1 (list b)) (vector 1 (list c)))))"));
    }
}
