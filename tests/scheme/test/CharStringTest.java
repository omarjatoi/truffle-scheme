package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.Test;

import scheme.runtime.value.SString;

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

    /**
     * M8-04: substring, string-append and string-copy! copy a string's array at once when both
     * strings have the same storage (Latin-1 or UTF-32), and character by character between
     * storages: every combination gives the same characters, overlapping copies within one string
     * included.
     */
    @Test
    public void copiesBetweenStorages() {
        // λ (#x3bb) makes a string UTF-32; the others are Latin-1.
        assertEquals("(\"bcd\" \"λb\" \"bc\" \"\")",
                        write("(list (substring \"abcde\" 1 4) (substring \"aλbc\" 1 3) (substring (string-append \"aλbc\") 2 4) (substring \"aλ\" 1 1))"));
        assertEquals("(\"abcdé\" \"abλcdλ\" \"λab\" \"ab\" \"\")",
                        write("(list (string-append \"ab\" \"c\" \"dé\") (string-append \"ab\" \"λ\" \"cd\" \"λ\") (string-append \"λ\" \"ab\") (string-append \"\" \"ab\" \"\") (string-append))"));
        // The result is a new mutable string, whatever it was copied from.
        assertEquals("(\"zbc\" \"zλ\")", write("(list (let ((s (string-append \"abc\"))) (string-set! s 0 #\\z) s) (let ((s (substring \"aλ\" 0 2))) (string-set! s 0 #\\z) s))"));
        // Latin-1 into UTF-32 and back, and overlapping copies forwards and backwards in each storage.
        assertEquals("(\"λxyλ\" \"aλλd\" \"ababc\" \"cdcde\" \"λaλab\" \"λaλaλ\")", write(
                        "(list (let ((s (string #\\x3bb #\\a #\\b #\\x3bb))) (string-copy! s 1 \"xy\") s)" +
                                        " (let ((s (string-copy \"abcd\"))) (string-copy! s 1 \"λλ\") s)" +
                                        " (let ((s (string-copy \"abcde\"))) (string-copy! s 2 s 0 3) s)" +
                                        " (let ((s (string-copy \"abcde\"))) (string-copy! s 0 s 2 4) s)" +
                                        " (let ((s (string #\\x3bb #\\a #\\b #\\c #\\d))) (string-copy! s 2 s 0 3) s)" +
                                        " (let ((s (string #\\x3bb #\\a #\\x3bb #\\b #\\c))) (string-copy! s 3 s 1 3) (string-copy! s 0 s 2 3) s))"));
        // Copying nothing into a literal changes nothing; copying something is an error.
        assertEquals("\"abc\"", write("(let ((s \"abc\")) (string-copy! s 0 \"\") s)"));
        assertTrue(error("(string-copy! \"abc\" 0 \"x\")").contains("immutable"));
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
    public void comparisonsAcrossStorages() {
        // Latin-1 and wide strings, each against each, by code point (M10-06: a loop per pair of
        // storages); "ÿ" is 255, the largest Latin-1 code point, and "Ā" 256.
        assertEquals("(#t #t #f #t #t #f #t #t #f)", write(
                        "(list (string<? \"ab\" \"aĀ\") (string<? \"aÿ\" \"aĀ\") (string<? \"aĀ\" \"aÿ\") (string>? \"aĀ\" \"ab\")" +
                                        " (string<? \"aĀ\" \"aā\") (string=? \"aĀ\" \"aā\") (string<? \"aĀ\" \"aĀb\") (string=? \"aĀ\" (string #\\a #\\x100)) (string>? \"aĀ\" \"aĀ\"))"));
        // A string that becomes wide by string-set! compares by its new code points.
        assertEquals("(#t #f #t)", write("(let ((s (make-string 2 #\\a))) (string-set! s 1 #\\x100) (list (string>? s \"ab\") (string<? s \"aa\") (string=? s \"aĀ\")))"));
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

    /**
     * Case folding is Unicode's CaseFolding.txt, which since Unicode 8.0 folds Cherokee to upper
     * case: R7RS erratum 25 replaces §6.6's "the result will be either a lowercase letter or the
     * same as the argument" for char-foldcase with "If the result of folding is not supported by
     * the implementation, the argument is returned". So an upper case Cherokee letter folds to
     * itself and a lower case one to its upper case, for characters, strings and the -ci
     * comparisons alike.
     */
    @Test
    public void cherokeeFoldsToUpperCase() {
        assertEquals("(5024 5024 5104 5104 5024 5104)",
                        write("(map char->integer (list (char-foldcase #\\x13a0) (char-foldcase #\\xab70) (char-foldcase #\\x13f0) (char-foldcase #\\x13f8) (char-upcase #\\xab70) (char-upcase #\\x13f8)))"));
        assertEquals("(43888 5112)", write("(map char->integer (list (char-downcase #\\x13a0) (char-downcase #\\x13f0)))"));
        assertEquals("(#t #t)", write("(list (equal? (string-foldcase (string #\\x13a0 #\\xab71)) (string #\\x13a0 #\\x13a1)) (string-ci=? (string #\\x13a0) (string #\\xab70)))"));
    }

    /**
     * The simple foldings (CaseFolding.txt status S) that lower(upper(c)) misses: Greek small
     * iota and upsilon with dialytika and tonos have two code points each, and so do the long s
     * t and s t ligatures; each folds to the other.
     */
    @Test
    public void simpleFoldingOfCanonicalDuplicates() {
        assertEquals("(912 944 64262 912 944 64262)", write(
                        "(map char->integer (list (char-foldcase #\\x1fd3) (char-foldcase #\\x1fe3) (char-foldcase #\\xfb05) (char-foldcase #\\x390) (char-foldcase #\\x3b0) (char-foldcase #\\xfb06)))"));
        assertEquals("(#t #t)", write("(list (char-ci=? #\\x1fd3 #\\x390) (char-ci=? #\\xfb05 #\\xfb06))"));
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

    /**
     * {@code SString}'s factories from Java text (a {@code String}, or a reader's {@code char[]})
     * give the same string as before they built it in one pass: one character per code point, a
     * surrogate pair one character and a lone surrogate one too, Latin-1 storage exactly when every
     * character is at most 255 (design.md D1), and a range of an array never pairs across its ends.
     */
    @Test
    public void stringsFromJavaText() {
        String latin1 = "x".repeat(9999) + "\u00ff";
        String[] samples = {"", "a", "\u0000", "\u007f\u0080\u00ff", "abc\u00e9\u00ff", "\u0100", "ab\u4e2d", "\u4e2dab\u00ff\u0080",
                        "\uD83D\uDE00", "a\uD83D\uDE00b\u00e9\u00ff", "\uD83D", "\uDE00", "\uD83Da", "a\uDE00\uD83D", "\uD83D\uD83D\uDE00\uDE00",
                        "\u00ff\uD800\uDC00\uDBFF\uDFFF\uFFFF", latin1, latin1 + "\u0101", latin1 + "\uD83D\uDE00\u00ff", "\uD83D\uDE00".repeat(5000)};
        for (String s : samples) {
            check(s, SString.of(s), false);
            check(s, SString.literal(s), true);
            char[] padded = ("\uDE00" + s + "\uDE00").toCharArray();
            check(s, SString.of(padded, 1, s.length()), false);
        }
        // A range that ends inside a pair, or starts inside one: its halves are lone surrogates.
        char[] pairs = "a\uD83D\uDE00b".toCharArray();
        check("a\uD83D", SString.of(pairs, 0, 2), false);
        check("\uDE00b", SString.of(pairs, 2, 2), false);
        check("", SString.of(pairs, 4, 0), false);
    }

    /**
     * {@code SString}'s factories from arrays a decoder filled (M11-06): the first {@code length}
     * Latin-1 bytes or code points, an array of exactly that length or a longer one, whose tail is
     * not part of the string. A string from code points has a character above 255 (its caller found
     * one), so it is stored wide (design.md D1).
     */
    @Test
    public void stringsFromFilledArrays() {
        String[] latin1 = {"", "a", "\u0000", "\u007f\u0080ÿ", "x".repeat(9999) + "ÿ"};
        for (String s : latin1) {
            byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
            byte[] longer = Arrays.copyOf(bytes, 2 * bytes.length + 3);
            // check stores into the string, so into the array it took.
            check(s, SString.ofLatin1(bytes, bytes.length), false);
            Arrays.fill(longer, bytes.length, longer.length, (byte) 'z');
            SString t = SString.ofLatin1(longer, bytes.length);
            Arrays.fill(longer, (byte) 'q');
            check(s, t, false);
        }
        String[] wide = {"Ā", "ab中", "中abÿ\u0080", "😀", "a😀béÿ", "�\u0000􏿿",
                        "x".repeat(9999) + "ā", "😀".repeat(5000)};
        for (String s : wide) {
            int[] cps = s.codePoints().toArray();
            int[] longer = Arrays.copyOf(cps, 2 * cps.length + 3);
            check(s, SString.ofCodePoints(cps, cps.length), false);
            Arrays.fill(longer, cps.length, longer.length, 'z');
            SString t = SString.ofCodePoints(longer, cps.length);
            Arrays.fill(longer, 'q');
            check(s, t, false);
        }
    }

    private static void check(String expected, SString s, boolean immutable) {
        int[] cps = expected.codePoints().toArray();
        assertEquals(expected, cps.length, s.length());
        for (int i = 0; i < cps.length; i++) {
            assertEquals(expected + " at " + i, cps[i], s.codePointAt(i));
        }
        assertEquals(expected, Arrays.stream(cps).allMatch(c -> c <= 0xff), s.isLatin1());
        assertEquals(immutable, s.isImmutable());
        assertEquals(expected, s.toString());
        if (!immutable && cps.length > 0) {
            // Still a string of its storage: storing a wide character widens a Latin-1 one.
            s.set(cps.length - 1, 0x1F600);
            assertEquals(0x1F600, s.codePointAt(cps.length - 1));
            assertFalse(s.isLatin1());
            assertEquals(cps.length > 1 ? cps[0] : 0x1F600, s.codePointAt(0));
        }
    }
}
