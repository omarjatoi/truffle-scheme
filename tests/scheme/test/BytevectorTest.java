package scheme.test;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.junit.Test;

/**
 * M4-04: bytevectors (R7RS §6.9). Results are compared as written. Errors are caught with
 * {@code guard} and reported as {@code error}, so each case checks that the primitive raises a
 * Scheme error object (design.md D8) rather than crashing.
 */
public class BytevectorTest {

    private static final String SCHEME = "scheme";

    private static String write(String expr) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            context.eval(SCHEME, "(write " + expr + ")");
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void check(String expected, String expr) {
        assertEquals(expr, expected, write(expr));
    }

    private static void error(String expr) {
        check("error", "(guard (e ((error-object? e) 'error)) " + expr + ")");
    }

    @Test
    public void predicate() {
        check("(#t #t #f #f #t #f)", "(list (bytevector? #u8()) (bytevector? #u8(0 1 2)) (bytevector? #()) (bytevector? '()) (bytevector? (make-bytevector 0)) (bytevector? \"abc\"))");
    }

    @Test
    public void construct() {
        check("#u8(0 1 255)", "(bytevector 0 1 255)");
        check("#u8()", "(bytevector)");
        check("#u8(0 0 0)", "(make-bytevector 3)");
        check("#u8(7 7)", "(make-bytevector 2 7)");
        check("(0 1024)", "(list (bytevector-length (make-bytevector 0)) (bytevector-length (make-bytevector 1024 255)))");
    }

    @Test
    public void refAndSet() {
        check("(0 2 255)", "(let ((bv (bytevector 0 1 2))) (list (bytevector-u8-ref bv 0) (bytevector-u8-ref bv 2) (bytevector-u8-ref #u8(255) 0)))");
        check("#u8(0 255 2)", "(let ((bv (bytevector 0 1 2))) (bytevector-u8-set! bv 1 255) bv)");
    }

    @Test
    public void copy() {
        check("(#u8() #u8(0 1 2) #u8(1 2) #u8(1))", "(list (bytevector-copy #u8()) (bytevector-copy #u8(0 1 2)) (bytevector-copy #u8(0 1 2) 1) (bytevector-copy #u8(0 1 2) 1 2))");
        // The copy is fresh and mutable, even from a literal.
        check("(#u8(9 1) #u8(0 1))", "(let* ((lit #u8(0 1)) (c (bytevector-copy lit))) (bytevector-u8-set! c 0 9) (list c lit))");
    }

    @Test
    public void copyInto() {
        check("#u8(1 6 7 4 5)", "(let ((bv (bytevector 1 2 3 4 5))) (bytevector-copy! bv 1 #u8(6 7 8 9 10) 0 2) bv)");
        check("#u8(8 9 10 4 5)", "(let ((bv (bytevector 1 2 3 4 5))) (bytevector-copy! bv 0 #u8(6 7 8 9 10) 2) bv)");
        // Overlapping source and destination, in both directions.
        check("#u8(1 1 2 4 5)", "(let ((bv (bytevector 1 2 3 4 5))) (bytevector-copy! bv 1 bv 0 2) bv)");
        check("#u8(3 4 5 4 5)", "(let ((bv (bytevector 1 2 3 4 5))) (bytevector-copy! bv 0 bv 2) bv)");
    }

    @Test
    public void append() {
        check("(#u8() #u8() #u8(0 1 2) #u8(0 1 2 3 4 5))",
                        "(list (bytevector-append) (bytevector-append #u8() #u8()) (bytevector-append #u8() #u8(0 1 2)) (bytevector-append #u8(0 1 2) #u8(3 4) #u8(5)))");
    }

    @Test
    public void utf8() {
        check("(\"ABC\" \"ABC\" \"ABC\" #t \"\")",
                        "(list (utf8->string #u8(#x41 #x42 #x43)) (utf8->string #u8(0 #x41 #x42 #x43) 1) (utf8->string #u8(0 #x41 #x42 #x43 0) 1 4) (equal? \"\\x3BB;\" (utf8->string #u8(0 #xCE #xBB 0) 1 3)) (utf8->string #u8()))");
        check("(#u8(65 66 67) #u8(66 67) #u8(66) #u8(206 187) #u8(240 159 152 128))",
                        "(list (string->utf8 \"ABC\") (string->utf8 \"ABC\" 1) (string->utf8 \"ABC\" 1 2) (string->utf8 \"λ\") (string->utf8 \"\\x1F600;\"))");
        check("(#t #t)", "(let ((s \"a\\x1F600;\\xE9;\")) (list (equal? s (utf8->string (string->utf8 s))) (string? (utf8->string (string->utf8 s)))))");
    }

    @Test
    public void literalsAreImmutable() {
        error("(bytevector-u8-set! #u8(1 2 3) 0 9)");
        error("(bytevector-copy! #u8(1 2 3) 0 #u8(4))");
    }

    @Test
    public void errors() {
        error("(bytevector 256)");
        error("(bytevector -1)");
        error("(bytevector 'a)");
        error("(make-bytevector -1)");
        error("(make-bytevector 2 256)");
        error("(bytevector-u8-ref #u8(1 2) 2)");
        error("(bytevector-u8-ref #u8(1 2) -1)");
        error("(bytevector-u8-ref '(1 2) 0)");
        error("(bytevector-u8-set! (bytevector 1 2) 0 256)");
        error("(bytevector-length #(1 2))");
        error("(bytevector-copy #u8(1 2) 3)");
        error("(bytevector-copy #u8(1 2) 2 1)");
        error("(bytevector-copy! (bytevector 1 2) 1 #u8(4 5))");
        error("(bytevector-append #u8(1) 'a)");
        error("(utf8->string #u8(#xff))");
        error("(utf8->string #u8(#xCE #xBB) 1)");
        error("(string->utf8 \"abc\" 4)");
        error("(string->utf8 'abc)");
    }
}
