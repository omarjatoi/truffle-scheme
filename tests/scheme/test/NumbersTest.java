package scheme.test;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.junit.Test;

/** M3-08: the numeric procedures sections 4.x and 5 and the test shim need (numeric tower area). */
public class NumbersTest {

    private static String write(String expr) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("scheme").out(out).build()) {
            context.eval("scheme", "(write " + expr + ")");
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void exactRationals() {
        assertEquals("1/3", write("(/ 1 3)"));
        assertEquals("2", write("(/ 6 3)"));
        assertEquals("1/2", write("(+ 1/3 1/6)"));
        assertEquals("1800/497", write("(/ 1800 497)"));
        assertEquals("#t", write("(< 1/3 1/2 1)"));
        assertEquals("0.5", write("(/ 1 2.)"));
        assertEquals("-1/2", write("(/ -2)"));
        assertEquals("#t", write("(= 1/2 0.5)"));
    }

    @Test
    public void predicates() {
        assertEquals("(#t #t #f #t #t #f)", write("(list (integer? 2) (integer? 2.0) (integer? 1/2) (rational? 1/2) (real? 1.5) (exact? 1.5))"));
        assertEquals("(#t #f #t #t #f)", write("(list (zero? 0) (zero? 1/2) (positive? 1/2) (even? 4) (odd? 4))"));
        assertEquals("(#t #f #t #t)", write("(list (exact-integer? 5) (exact-integer? 5.0) (number? 1/2) (nan? (/ 0. 0.)))"));
    }

    @Test
    public void procedures() {
        assertEquals("(5 5/2 9 0 1/2)", write("(list (abs -5) (abs -5/2) (square 3) (imag-part 3) (real-part 1/2))"));
        assertEquals("(1267650600228229401496703205376 1/8 1.4142135623730951)", write("(list (expt 2 100) (expt 2 -3) (expt 2. .5))"));
        assertEquals("(5 7)", write("(call-with-values (lambda () (exact-integer-sqrt 32)) list)"));
        assertEquals("(\"1100\" \"ff\" \"-7/2\" \"0.25\")", write("(list (number->string 12 2) (number->string 255 16) (number->string -7/2) (number->string .25))"));
        assertEquals("(3 2/3 1.4142135623730951 1/4 #t)", write("(list (sqrt 9) (sqrt 4/9) (sqrt 2) (exact .25) (inexact? (inexact 1/3)))"));
        assertEquals("(1.0 0.0 2.0)", write("(list (exp 0) (log 1) (log 100 10))"));
    }

    @Test
    public void signedZerosAndBranchCuts() {
        // M4-12 (Larceny's inexact and complex tests): negation flips a zero's sign, abs clears it.
        assertEquals("(-0.0 0.0 -0.0 0.0 0 -1/2)", write("(list (- 0.0) (- -0.0) (- -0.0 0.0) (abs -0.0) (- 0) (- 1/2))"));
        assertEquals("-0.0-1.0i", write("(- 0.0+1.0i)"));
        // -0.0 is on the negative side of log's branch cut; a negative real's log is complex.
        assertEquals("(-inf.0 #t #t)", write("(let ((z (log -0.0))) (list (real-part z) (= (imag-part z) (acos -1)) (= (log -1) (make-rectangular 0.0 (acos -1)))))"));
        assertEquals("-inf.0", write("(log 0.0)"));
        assertEquals("+nan.0", write("(log +nan.0)"));
        // No rational is within any distance of an infinity: (rationalize +inf.0 +inf.0) is NaN.
        assertEquals("(+nan.0 0.0 +inf.0 +nan.0)", write("(list (rationalize +inf.0 +inf.0) (rationalize 3.0 +inf.0) (rationalize +inf.0 3.0) (rationalize +nan.0 1.0))"));
    }
}
