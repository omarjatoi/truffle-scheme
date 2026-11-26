package scheme.test;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.junit.Test;

/**
 * M12-04: {@code memq}, {@code memv}, {@code assq} and {@code assv}'s loop chooses how to compare
 * from the key once, before it walks the list (a character's code point, the identity of a key
 * that is no number, character or boolean, or {@code eqv?}'s generic comparison); the results are
 * the primitives' as before. The lists here are made at run time, so the compiler's constant
 * comparisons (ConstantMember) don't apply and the primitive runs.
 */
public class MemberKeyTest {

    private static String write(String expr) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("scheme").out(out).build()) {
            context.eval("scheme", "(define l (list 'a \"s\" #\\x 1 2.5 #t '() (expt 10 20) 1/2 1+2i #\\λ 'b #\\x))\n(define s (cadr l))\n(write " + expr + ")");
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void charactersCompareByCodePoint() {
        assertEquals("((#\\x 1 2.5 #t () 100000000000000000000 1/2 1+2i #\\λ b #\\x) (#\\λ b #\\x) #f #t)",
                        write("(list (memv (string-ref \"x\" 0) l) (memq (integer->char 955) l) (memv #\\X l) (eq? (memv #\\x l) (cddr l)))"));
        assertEquals("((#\\b . 2) (#\\b . 2) #f)",
                        write("(let ((al (list (cons #\\a 1) (cons #\\b 2) (cons #\\b 3)))) (list (assv (integer->char 98) al) (assq #\\b al) (assv #\\c al)))"));
    }

    @Test
    public void otherKeysCompareByIdentity() {
        assertEquals("((b #\\x) #f (\"s\" #\\x 1 2.5 #t () 100000000000000000000 1/2 1+2i #\\λ b #\\x) #f (() 100000000000000000000 1/2 1+2i #\\λ b #\\x))",
                        write("(list (memq 'b l) (memv \"s\" l) (memv s l) (memv (vector) l) (memq '() l))"));
    }

    @Test
    public void numbersAndBooleansCompareAsBefore() {
        assertEquals("((1) (2.5) (#t) (100000000000000000000) (1/2) (1+2i) #f #f)",
                        write("(map (lambda (k) (let ((t (memv k l))) (if t (list (car t)) #f))) (list (- 2 1) (/ 5. 2) (not #f) (expt 10 20) (/ 2 4) (make-rectangular 1 2) 1. 2))"));
        assertEquals("((1 . one) #f (100000000000000000000 . big))",
                        write("(let ((al (list (cons 1 'one) (cons (expt 10 20) 'big)))) (list (assv (- 3 2) al) (assv 1. al) (assv (expt 10 20) al)))"));
    }

    @Test
    public void improperAndCircularListsStillRaise() {
        assertEquals("(\"memv: argument 2 is not a proper list\" \"memq: argument 2 is not a proper list\" \"assv: argument 2 has an element that is not a pair\")",
                        write("(map (lambda (thunk) (guard (e ((error-object? e) (error-object-message e))) (thunk) 'none)) " +
                                        "(list (lambda () (memv #\\z (cons #\\a #\\b))) (lambda () (let ((c (list 'a 'b))) (set-cdr! (cdr c) c) (memq 'z c))) " +
                                        "(lambda () (assv #\\z (list (cons #\\a 1) 2)))))"));
    }
}
