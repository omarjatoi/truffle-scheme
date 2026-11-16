package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
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

    /** A circular list (1 2 3 1 2 3 ...) bound to c, and a circular alist bound to a, around expr. */
    private static String circular(String expr) {
        return "(let ((c (list 1 2 3)) (a (list (cons 1 'one) (cons 2 'two)))) (set-cdr! (cddr c) c) (set-cdr! (cdr a) a) " + expr + ")";
    }

    /** What expr raises, as (message irritants-are-the-circular-list?), or its value. */
    private static String raisesOnCircular(String expr) {
        return write(circular("(guard (e ((error-object? e) (list (error-object-message e) (eq? (car (error-object-irritants e)) c)))) " + expr + ")"));
    }

    /**
     * M7-06: R7RS §6.4 says these procedures take a list, which a circular list is not, so passing
     * one "is an error" (§1.3.2); we raise instead of looping forever (design.md D9), naming the
     * procedure and with the list as the irritant.
     */
    @Test
    public void circularListsRaise() {
        String[] procedures = {"length", "reverse", "list-copy", "list->vector", "list->string"};
        for (String procedure : procedures) {
            assertEquals(procedure, "(\"" + procedure + ": not a proper list\" #t)", raisesOnCircular("(" + procedure + " c)"));
        }
        assertEquals("(\"append: not a proper list\" #t)", raisesOnCircular("(append '(0) c '(4))"));
        assertEquals("(\"apply: not a proper list\" #t)", raisesOnCircular("(apply + 0 c)"));
        // Without the element, memq and friends would walk a circular list forever.
        for (String procedure : new String[]{"memq", "memv", "member"}) {
            assertEquals(procedure, "(\"" + procedure + ": not a proper list\" #t)", raisesOnCircular("(" + procedure + " 4 c)"));
        }
        assertEquals("(\"member: not a proper list\" #t)", raisesOnCircular("(member 4 c =)"));
        for (String procedure : new String[]{"assq", "assv", "assoc"}) {
            assertEquals(procedure, "#t", write(circular("(guard (e ((error-object? e) (eq? (car (error-object-irritants e)) a))) (" + procedure + " 3 a))")));
        }
        assertEquals("#t", write(circular("(guard (e ((error-object? e) (eq? (car (error-object-irritants e)) a))) (assoc 3 a =))")));
        // With it, they return the first sublist (or pair) that has it, as for any list.
        assertEquals("(2 3 1 2 3)", write(circular("(let loop ((l (memv 2 c)) (n 5) (acc '())) (if (= n 0) (reverse acc) (loop (cdr l) (- n 1) (cons (car l) acc))))")));
        assertEquals("((2 . two) (2 . two) (1 . one) (2 . two) #t #t)", write(circular("(list (assq 2 a) (assoc 2.0 a =) (assv 1 a) (assoc 2 a) (eq? (member 1 c) c) (eq? (memq 3 c) (cddr c)))")));
        // map and for-each stop at the shortest list, which may be the only finite one (§6.10)...
        assertEquals("((1 a) (2 b) (3 c) (1 d))", write(circular("(map list c '(a b c d))")));
        assertEquals("(1 3 2 1)", write(circular("(let ((acc '())) (for-each (lambda (x y) (set! acc (cons x acc))) c '(a b c d)) acc)")));
        // ... but it is an error if every list is circular.
        assertEquals("(\"map: every list is circular\" #t)", raisesOnCircular("(map (lambda (x) x) c)"));
        assertEquals("(\"for-each: every list is circular\" #t)", raisesOnCircular("(for-each (lambda (x y) x) c c)"));
        assertEquals("(\"map: every list is circular\" #t)", raisesOnCircular("(map + c (cdr c))"));
        // Bounded walks and list? need nothing new.
        assertEquals("(2 (3 1 2 3 1 2 3 1) #f)", write(circular(
                        "(list (list-ref c 100) (let loop ((l (list-tail c 101)) (n 8) (acc '())) (if (= n 0) (reverse acc) (loop (cdr l) (- n 1) (cons (car l) acc)))) (list? c))")));
        // A cycle that starts further in, and a long one.
        assertEquals("(\"length: not a proper list\" #t)", raisesOnCircular("(let ((l (list 0 1 2 3 4 5 6 7 8 9))) (set-cdr! (list-tail l 9) (list-tail l 4)) (set! c l) (length l))"));
        assertEquals("(\"reverse: not a proper list\" #t)", raisesOnCircular("(let ((l (make-list 100000 0))) (set-cdr! (list-tail l 99999) l) (set! c l) (reverse l))"));
        // An improper list is still an error, and proper ones work as before.
        assertEquals("error", raises("(length '(1 2 . 3))"));
        assertEquals("error", raises("(reverse '(1 . 2))"));
        assertEquals("(3 (3 2 1) 0 ())", write("(list (length '(a b c)) (reverse '(1 2 3)) (length '()) (reverse '()))"));
        assertEquals("((a b) #f (b c) #f (2 . b) #f)", write("(list (memq 'a '(a b)) (memq 'z '(a b)) (memv 'b '(a b c)) (memv 1 '(a . b)) (assv 2 '((1 . a) (2 . b))) (assq 'x '()))"));
    }

    /**
     * The error object names the circular list as its irritant: printing it (an uncaught error's
     * message, display, write) terminates, with datum labels where write needs them (design.md D9).
     */
    @Test
    public void circularIrritantsPrint() {
        assertEquals("#0=(1 2 3 . #0#)", write(circular("(guard (e (#t (car (error-object-irritants e)))) (length c))")));
        try (Context context = Context.create("scheme")) {
            PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval("scheme", circular("(length c)")));
            assertTrue(e.getMessage(), e.getMessage().contains("length: not a proper list #0=(1 2 3 . #0#)"));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("scheme").out(out).build()) {
            context.eval("scheme", circular("(display c)"));
        }
        assertTrue(out.toString(StandardCharsets.UTF_8), out.toString(StandardCharsets.UTF_8).startsWith("#0=(1 2 3 . #0#)"));
    }
}
