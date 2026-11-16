package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.Test;

/** M4-02: characters, strings and symbols (R7RS §6.5–6.7, design.md D9). */
public class CharStringTest {

    private static String write(String expr) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("scheme").out(out).build()) {
            context.eval("scheme", "(write " + expr + ")");
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** The message of the error {@code expr} raises. */
    private static String error(String expr) {
        try (Context context = Context.newBuilder("scheme").out(new ByteArrayOutputStream()).err(new ByteArrayOutputStream()).build()) {
            context.eval("scheme", expr);
        } catch (PolyglotException e) {
            assertTrue(e.isGuestException());
            return e.getMessage();
        }
        throw new AssertionError("no error from " + expr);
    }

    @Test
    public void symbols() {
        assertEquals("(#t #f #t #f)", write("(list (symbol=? 'a 'a) (symbol=? 'a 'A) (symbol=? 'a 'a 'a) (symbol=? 'a 'a 'b))"));
        assertEquals("(\"flying-fish\" \"Martin\" mISSISSIppi #t)",
                        write("(list (symbol->string 'flying-fish) (symbol->string 'Martin) (string->symbol \"mISSISSIppi\") (eq? 'bitBlt (string->symbol \"bitBlt\")))"));
        assertEquals("|a b|", write("(string->symbol \"a b\")"));
    }

    @Test
    public void characterPredicates() {
        assertEquals("(#t #f #t #f)", write("(list (char? #\\a) (char? \"a\") (char=? #\\a #\\a #\\a) (char<? #\\a #\\a))"));
        assertEquals("(#t #t #f #t #t)", write("(list (char-ci=? #\\a #\\A #\\a) (char-ci<? #\\a #\\B #\\c) (char-ci>? #\\a #\\A) (char>=? #\\b #\\b #\\a) (char<=? #\\a #\\b #\\b))"));
        assertEquals("(#t #f #t #t #f #t)",
                        write("(list (char-alphabetic? #\\x39B) (char-alphabetic? #\\x0E50) (char-numeric? #\\x0E50) (char-whitespace? #\\x1680) (char-whitespace? #\\_) (char-upper-case? #\\x39B))"));
        assertEquals("(0 4 0 #f #f)", write("(list (digit-value #\\0) (digit-value #\\x0664) (digit-value #\\x0AE6) (digit-value #\\.) (digit-value #\\a))"));
    }

    @Test
    public void characterConversions() {
        assertEquals("(97 #\\a #\\A #\\a #\\a)", write("(list (char->integer #\\a) (integer->char 97) (char-upcase #\\a) (char-downcase #\\A) (char-foldcase #\\A))"));
        // Simple case mapping: a character maps to one character (D9).
        assertEquals("(223 955 923 963 304 305)", write(
                        "(map char->integer (list (char-upcase #\\xdf) (char-foldcase #\\x39b) (char-upcase #\\x3bb) (char-foldcase #\\x3c2) (char-foldcase #\\x130) (char-foldcase #\\x131)))"));
        assertTrue(error("(integer->char #xd800)").contains("integer->char"));
    }

    @Test
    public void basicStrings() {
        assertEquals("(3 \"---\" \"\" \"kitten\")", write("(list (string-length (make-string 3)) (make-string 3 #\\-) (string) (string #\\k #\\i #\\t #\\t #\\e #\\n))"));
        assertEquals("(#\\a #\\c \"a-c\")", write("(list (string-ref \"abc\" 0) (string-ref \"abc\" 2) (let ((s (string #\\a #\\b #\\c))) (string-set! s 1 #\\-) s))"));
        assertEquals("#t", write("(equal? (string #\\a #\\x1F700 #\\c) (let ((s (string #\\a #\\b #\\c))) (string-set! s 1 #\\x1F700) s))"));
        assertEquals("(\"\" \"ab\" \"bc\" (#\\b #\\c) \"abc\")", write(
                        "(list (substring \"abc\" 1 1) (substring \"abc\" 0 2) (string-copy \"abc\" 1) (string->list \"abc\" 1 3) (list->string '(#\\a #\\b #\\c)))"));
        assertTrue(error("(string-set! \"abc\" 0 #\\x)").contains("immutable"));
        assertTrue(error("(string-ref \"abc\" 3)").contains("string-ref"));
        assertTrue(error("(substring \"abc\" 2 1)").contains("substring"));
    }

    @Test
    public void mutation() {
        assertEquals("(\"-----\" \"xx---\" \"xx-xx\")",
                        write("(map (lambda (args) (let ((s (make-string 5 #\\x))) (apply string-fill! s #\\- args) s)) '(() (2) (2 3)))"));
        assertEquals("(\"a12de\" \"aabde\" \"abcab\")",
                        write("(list (let ((s (string-copy \"abcde\"))) (string-copy! s 1 \"12345\" 0 2) s) (let ((s (string-copy \"abcde\"))) (string-copy! s 1 s 0 2) s) (let ((s (string-copy \"abcde\"))) (string-copy! s 3 s 0 2) s))"));
        // Storing a character above 255 widens a Latin-1 string (D1).
        assertEquals("\"xλx\"", write("(let ((s (make-string 3 #\\x))) (string-fill! s #\\x3bb 1 2) s)"));
        assertTrue(error("(string-copy! (make-string 2) 0 \"abc\")").contains("string-copy!"));
    }

    @Test
    public void comparisons() {
        assertEquals("(#t #f #t #f #t #t)", write(
                        "(list (string=? \"abc\" \"abc\" \"abc\") (string=? \"abc\" \"aBc\") (string<? \"abc\" \"abcd\" \"acd\") (string>? \"abc\" \"abcd\") (string<=? \"\" \"\") (string>=? \"abcd\" \"abcd\" \"abc\"))"));
        assertEquals("(#t #f #t #t)", write("(list (string-ci=? \"abc\" \"aBc\") (string-ci<? \"abc\" \"aBc\") (string-ci>? \"ABCd\" \"aBc\") (string-ci=? \"ΑΒΓ\" \"αβγ\"))"));
        // Full case folding: Straße and STRASSE are the same.
        assertEquals("#t", write("(string-ci=? \"Straße\" \"STRASSE\")"));
    }

    @Test
    public void caseMapping() {
        // Full case mapping (D9).
        assertEquals("(\"SSA\" \"ssa\" \"maß\" \"mass\")", write("(list (string-upcase \"ßa\") (string-downcase \"SSA\") (string-downcase \"Maß\") (string-foldcase \"Maß\"))"));
        assertEquals("(\"i̇\" \"i̇\" \"J̌\" \"s\")", write("(list (string-downcase \"İ\") (string-foldcase \"İ\") (string-upcase \"ǰ\") (string-foldcase \"ſ\"))"));
        // Final sigma: downcase is context-sensitive, foldcase isn't.
        assertEquals("(\"μέλος\" \"μέλοσ\")", write("(list (string-downcase \"ΜΈΛΟΣ\") (string-foldcase \"ΜΈΛΟΣ\"))"));
        // Locale.ROOT, never the default locale: no Turkish dotless i.
        assertEquals("\"TITLE\"", write("(string-upcase \"title\")"));
    }

    @Test
    public void mapAndForEach() {
        assertEquals("\"ABC\"", write("(string-map char-upcase \"abc\")"));
        assertEquals("\"bdf\"", write("(string-map (lambda (a b) (integer->char (+ (char->integer a) (- (char->integer b) 48)))) \"abcd\" \"123\")"));
        assertEquals("(#\\c #\\b #\\a)", write("(let ((acc '())) (string-for-each (lambda (c) (set! acc (cons c acc))) \"abc\") acc)"));
        assertEquals("((#\\b . #\\y) (#\\a . #\\x))", write("(let ((acc '())) (string-for-each (lambda (c d) (set! acc (cons (cons c d) acc))) \"ab\" \"xyz\") acc)"));
        // Re-entering string-map's procedure never changes an earlier result (D5).
        assertEquals("(\"ab\" \"xb\")", write("""
                        (let ((k #f) (n 0) (results '()))
                          (let ((r (string-map (lambda (c) (if (char=? c #\\a) (call/cc (lambda (c2) (set! k c2) #\\a)) c)) "ab")))
                            (set! results (cons r results))
                            (set! n (+ n 1))
                            (if (= n 1) (k #\\x) (reverse results))))"""));
    }
}
