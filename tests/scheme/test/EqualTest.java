package scheme.test;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.junit.Test;

/** M3-08: equal? (R7RS §6.1, design.md D9): structural, iterative, and terminating on cycles. */
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
}
