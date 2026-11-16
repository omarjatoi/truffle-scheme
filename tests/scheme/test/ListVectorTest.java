package scheme.test;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.junit.Test;

/** M4-03: the list and vector procedures of R7RS §6.4 and §6.8, and (scheme cxr). */
public class ListVectorTest {

    private static String write(String expr) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("scheme").out(out).build()) {
            context.eval("scheme", "(write " + expr + ")");
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Evaluates {@code expr} and reports whether it raised an error object. */
    private static String raises(String expr) {
        return write("(guard (e ((error-object? e) 'error)) " + expr + ")");
    }

    @Test
    public void listPredicate() {
        assertEquals("(#t #t #f #f #f)", write("(list (list? '(a b c)) (list? '()) (list? '(a . b)) (list? 3) (list? #(1)))"));
        assertEquals("#f", write("(let ((x (list 'a))) (set-cdr! x x) (list? x))"));
        assertEquals("#f", write("(let ((x (list 1 2 3 4 5))) (set-cdr! (cddddr x) (cdr x)) (list? x))"));
    }

    @Test
    public void makeListAndIndexing() {
        assertEquals("((3 3) () 2)", write("(list (make-list 2 3) (make-list 0) (length (make-list 2)))"));
        assertEquals("((d e) () c)", write("(list (list-tail '(a b c d e) 3) (list-tail '(a b) 2) (list-ref '(a b c d) 2))"));
        assertEquals("(0 x 2)", write("(let ((l (list 0 1 2))) (list-set! l 1 'x) l)"));
        assertEquals("error", raises("(list-ref '(a b) 2)"));
        assertEquals("error", raises("(list-tail '(a b) 3)"));
        assertEquals("error", raises("(list-ref '(a b) -1)"));
        assertEquals("error", raises("(make-list -1)"));
    }

    @Test
    public void listCopy() {
        assertEquals("((1 2 3) \"foo\" () (3 . 4))", write("(list (list-copy '(1 2 3)) (list-copy \"foo\") (list-copy '()) (list-copy '(3 . 4)))"));
        assertEquals("(#t #f)", write("(let* ((a '((x) y)) (b (list-copy a))) (list (eq? (car a) (car b)) (eq? (cdr a) (cdr b))))"));
    }

    @Test
    public void memberAndAssoc() {
        assertEquals("((a) c)", write("(member (list 'a) '(b (a) c))"));
        assertEquals("(4 5)", write("(member 3 '(1 4 5) <)"));
        assertEquals("#f", write("(member 9 '(1 2))"));
        assertEquals("((a))", write("(assoc (list 'a) '(((a)) ((b))))"));
        assertEquals("(2 4)", write("(assoc 2.0 '((1 1) (2 4) (3 9)) =)"));
        assertEquals("#f", write("(assoc 'z '((a 1)))"));
    }

    @Test
    public void cxr() {
        assertEquals("(1 2 3 4)", write("(let ((x '(((1) 2) 3 4))) (list (caaar x) (cadaar '((((0) 2)))) (cadr x) (cadddr '(0 1 2 4))))"));
        assertEquals("(5)", write("(cddddr '(1 2 3 4 5))"));
    }

    @Test
    public void vectorConversions() {
        assertEquals("((a b c) (b c) (b))", write("(list (vector->list #(a b c)) (vector->list #(a b c) 1) (vector->list #(a b c) 1 2))"));
        assertEquals("(#(#\\A #\\B) #(#\\B) \"bc\" \"b\")",
                        write("(list (string->vector \"AB\") (string->vector \"AB\" 1) (vector->string #(#\\a #\\b #\\c) 1) (vector->string #(#\\a #\\b #\\c) 1 2))"));
        assertEquals("\"λx\"", write("(vector->string (vector #\\λ #\\x))"));
        assertEquals("error", raises("(vector->list #(a b c) 2 1)"));
        assertEquals("error", raises("(vector->string #(1 2))"));
        assertEquals("error", raises("(string->vector \"abc\" 4)"));
    }

    @Test
    public void copyAppendFill() {
        assertEquals("(#(a b c) #(b) #t)", write("(let ((v #(a b c))) (list (vector-copy v) (vector-copy v 1 2) (not (eq? v (vector-copy v)))))"));
        assertEquals("#(a b c d e f)", write("(vector-append #(a b c) #(d e) #() #(f))"));
        assertEquals("#(1 2 x x 5)", write("(let ((v (vector 1 2 3 4 5))) (vector-fill! v 'x 2 4) v)"));
        assertEquals("#(1 1 2 4 5)", write("(let ((v (vector 1 2 3 4 5))) (vector-copy! v 1 v 0 2) v)"));
        assertEquals("#(2 3 3 4 5)", write("(let ((v (vector 1 2 3 4 5))) (vector-copy! v 0 v 1 3) v)"));
        assertEquals("error", raises("(vector-copy! (vector 1 2) 1 #(a b))"));
        assertEquals("error", raises("(vector-fill! #(1 2) 0)"));
        assertEquals("error", raises("(vector-copy! #(1 2) 0 #(a))"));
        assertEquals("error", raises("(vector-append #(1) '(2))"));
        assertEquals("#t", write("(begin (vector-set! (vector-copy #(1 2)) 0 'x) #t)"));
    }

    @Test
    public void vectorMapAndForEach() {
        assertEquals("#(b e h)", write("(vector-map cadr '#((a b) (d e) (g h)))"));
        assertEquals("#(5 7 9)", write("(vector-map + #(1 2 3) #(4 5 6 7))"));
        assertEquals("(4 1 0)", write("(let ((acc '())) (vector-for-each (lambda (x) (set! acc (cons (* x x) acc))) #(0 1 2)) acc)"));
        assertEquals("(3 5)", write("(let ((acc '())) (vector-for-each (lambda (x y) (set! acc (cons (+ x y) acc))) #(1 2) #(2 3 4)) (reverse acc))"));
    }

    /** R7RS §6.10: vector-map must not change an earlier result when its continuation is re-entered. */
    @Test
    public void vectorMapReentry() {
        assertEquals("(#(1 2) #(1 3))",
                        write("(let ((k #f) (results '())) (let ((v (vector-map (lambda (x) (if (= x 2) (call/cc (lambda (c) (set! k c) 2)) x)) #(1 2)))) " +
                                        "(set! results (cons v results)) (if (= (length results) 1) (k 3)) (reverse results)))"));
    }
}
