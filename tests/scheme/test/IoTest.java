package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * M4-06: input and output (R7RS §6.13): string, bytevector and file ports, textual and binary
 * input, read (with read errors, design.md D8), the current-port parameters, and the procedures that
 * call a procedure with a port (design.md D5). File ports go through TruffleFile (D10), so the
 * file tests run in a context with I/O allowed. Results are compared as written.
 */
public class IoTest {

    private static final String SCHEME = "scheme";

    private Path dir;

    @Before
    public void createDirectory() throws IOException {
        dir = Files.createTempDirectory("io-test");
    }

    @After
    public void deleteDirectory() throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static String run(Context.Builder builder, String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = builder.out(out).build()) {
            context.eval(SCHEME, program);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String run(String program) {
        return run(Context.newBuilder(SCHEME), program);
    }

    private static String runWithFiles(String program) {
        return run(Context.newBuilder(SCHEME).allowIO(IOAccess.ALL), program);
    }

    private static void check(String expected, String expr) {
        assertEquals(expr, expected, run("(write " + expr + ")"));
    }

    /** A Scheme string literal naming a file in the test directory. */
    private String file(String name) {
        return "\"" + dir.resolve(name).toString().replace("\\", "\\\\") + "\"";
    }

    @Test
    public void portPredicates() {
        check("(#t #t #f #t #f)", "(let ((p (open-input-string \"x\"))) (list (port? p) (input-port? p) (output-port? p) (textual-port? p) (binary-port? p)))");
        check("(#t #f #t #f #t)", "(let ((p (open-output-bytevector))) (list (port? p) (input-port? p) (output-port? p) (textual-port? p) (binary-port? p)))");
        check("(#t #t #t)", "(list (input-port? (current-input-port)) (output-port? (current-output-port)) (output-port? (current-error-port)))");
        check("(#t #f #f #t)", "(let ((i (open-input-string \"\")) (o (open-output-string))) (list (input-port-open? i) (output-port-open? i) (input-port-open? o) (output-port-open? o)))");
        check("(#f #f)", "(let ((i (open-input-string \"\")) (o (open-output-string))) (close-input-port i) (close-port o) (close-port o) (list (input-port-open? i) (output-port-open? o)))");
        check("(#t #f)", "(list (eof-object? (eof-object)) (eof-object? '()))");
    }

    @Test
    public void textualInput() {
        check("(#\\a #\\a #\\b #t)", "(let ((p (open-input-string \"ab\"))) (let* ((a (peek-char p)) (b (read-char p)) (c (read-char p))) (list a b c (eof-object? (read-char p)))))");
        // Characters outside the BMP are one character each.
        check("(#\\\uD83D\uDE00 #\\z #t)", "(let ((p (open-input-string \"\\x1F600;z\"))) (let* ((a (read-char p)) (b (read-char p))) (list a b (eof-object? (peek-char p)))))");
        check("(\"one\" \"two\" \"\" \"three\" #t)",
                        "(let ((p (open-input-string \"one\\ntwo\\r\\n\\nthree\"))) (let* ((a (read-line p)) (b (read-line p)) (c (read-line p)) (d (read-line p))) (list a b c d (eof-object? (read-line p)))))");
        check("(\"ab\" \"c\" #t \"\")", "(let ((p (open-input-string \"abc\"))) (let* ((a (read-string 2 p)) (b (read-string 5 p))) (list a b (eof-object? (read-string 1 p)) (read-string 0 p))))");
        check("#t", "(char-ready? (open-input-string \"\"))");
        check("#\\q", "(parameterize ((current-input-port (open-input-string \"q\"))) (read-char))");
    }

    @Test
    public void read() {
        check("((a . b) #(1 \"s\") 2.5 #t)", "(let ((p (open-input-string \"(a . b) #(1 \\\"s\\\") 2.5\"))) (let* ((a (read p)) (b (read p)) (c (read p))) (list a b c (eof-object? (read p)))))");
        // read leaves the rest of the input in the port, for read-char.
        check("(foo #\\space #\\()", "(let ((p (open-input-string \"foo (bar)\"))) (let* ((a (read p)) (b (read-char p)) (c (read-char p))) (list a b c)))");
        // #!fold-case lasts across reads from the same port.
        check("(abc def)", "(let ((p (open-input-string \"#!fold-case ABC DEF\"))) (let* ((a (read p)) (b (read p))) (list a b)))");
        check("(1 . #0=(2 . #0#))", "(read (open-input-string \"(1 . #0=(2 . #0#))\"))");
        check("(1/2 0+i 17)", "(list (read (open-input-string \"1/2\")) (read (open-input-string \"+i\")) (read (open-input-string \"#x11\")))");
    }

    @Test
    public void readErrors() {
        check("(read-error #t #f)", "(guard (e (#t (list 'read-error (read-error? e) (file-error? e)))) (read (open-input-string \"(1 2\")))");
        check("#t", "(guard (e (#t (read-error? e))) (read (open-input-string \"(a . #;b)\")))");
        check("#t", "(guard (e (#t (read-error? e))) (read (open-input-string \")\")))");
        check("#t", "(guard (e (#t (read-error? e))) (read (open-input-string \"#\\\\nosuchname\")))");
    }

    @Test
    public void closedAndWrongPorts() {
        check("error", "(let ((p (open-input-string \"abc\"))) (close-port p) (guard (e (#t 'error)) (read-char p)))");
        check("error", "(let ((p (open-output-string))) (close-port p) (guard (e (#t 'error)) (display 1 p)))");
        check("error", "(guard (e (#t 'error)) (write-char #\\a (open-output-bytevector)))");
        check("error", "(guard (e (#t 'error)) (read-u8 (open-input-string \"a\")))");
        check("error", "(guard (e (#t 'error)) (read-char (open-output-string)))");
        check("error", "(guard (e (#t 'error)) (write-string \"abc\" (open-output-string) 2 1))");
    }

    @Test
    public void textualOutput() {
        check("\"bcd|x\\n|\\\"q\\\"|#0=(1 . #0#)\"", """
                        (let ((p (open-output-string)) (x (list 1)))
                          (set-cdr! x x)
                          (write-string "abcde" p 1 4) (write-char #\\| p) (display "x" p) (newline p) (write-string "|" p)
                          (write "q" p) (write-string "|" p 1 1) (write-string "|" p 0) (write x p)
                          (get-output-string p))""");
        check("\"(#0=(1) #0#)|((1) (1))\"", """
                        (let ((p (open-output-string)) (x (list 1)))
                          (write-shared (list x x) p) (write-string "|" p) (write-simple (list x x) p)
                          (get-output-string p))""");
    }

    @Test
    public void binaryPorts() {
        check("(1 1 2 #t)", "(let ((p (open-input-bytevector #u8(1 2)))) (let* ((a (peek-u8 p)) (b (read-u8 p)) (c (read-u8 p))) (list a b c (eof-object? (read-u8 p)))))");
        check("(#u8(1 2) #u8(3) #t #t)",
                        "(let ((p (open-input-bytevector #u8(1 2 3)))) (let* ((a (read-bytevector 2 p)) (b (read-bytevector 2 p))) (list a b (eof-object? (read-bytevector 2 p)) (u8-ready? p))))");
        // copy makes a fresh mutable bytevector with the bytevector ports alone (no other area's procedures).
        check("(2 #u8(0 7 8 0) 1 #u8(9 7 8 0))", """
                        (let* ((copy (lambda (bv) (let ((o (open-output-bytevector))) (write-bytevector bv o) (get-output-bytevector o))))
                               (p (open-input-bytevector #u8(7 8 9)))
                               (bv (copy #u8(0 0 0 0))))
                          (let* ((n (read-bytevector! bv p 1 3)) (before (copy bv)) (m (read-bytevector! bv p)))
                            (list n before m bv)))""");
        check("#u8(1 2 3 5 6)",
                        "(let ((p (open-output-bytevector))) (write-u8 1 p) (write-bytevector #u8(2 3) p) (write-bytevector #u8(4 5 6 7) p 1 3) (flush-output-port p) (get-output-bytevector p))");
    }

    @Test
    public void callWithPort() {
        check("(\"xy\" #f)", "(let* ((p (open-output-string)) (r (call-with-port p (lambda (q) (write-string \"xy\" q) (get-output-string q))))) (list r (output-port-open? p)))");
        check("(1 2)", "(call-with-values (lambda () (call-with-port (open-input-string \"\") (lambda (p) (values 1 2)))) list)");
    }

    @Test
    public void filePorts() throws IOException {
        String out = runWithFiles("""
                        (define f %s)
                        (write (file-exists? f))
                        (call-with-output-file f (lambda (p) (write '(hello "world" #\\λ) p) (newline p) (display "second line" p)))
                        (write (file-exists? f))
                        (write (call-with-input-file f (lambda (p) (let* ((d (read p)) (rest (read-line p)) (l (read-line p))) (list d rest l (eof-object? (read-line p)))))))
                        (with-output-to-file f (lambda () (display "replaced")))
                        (write (with-input-from-file f (lambda () (read-line))))
                        (delete-file f)
                        (write (file-exists? f))""".formatted(file("a.txt")));
        assertEquals("#f#t((hello \"world\" #\\λ) \"\" \"second line\" #t)\"replaced\"#f", out);
        assertFalse(Files.exists(dir.resolve("a.txt")));
    }

    @Test
    public void binaryFilePorts() throws IOException {
        String out = runWithFiles("""
                        (define f %s)
                        (let ((p (open-binary-output-file f))) (write-bytevector #u8(0 255 10) p) (write-u8 7 p) (close-port p))
                        (let ((p (open-binary-input-file f))) (write (list (binary-port? p) (read-bytevector 10 p))) (close-input-port p))""".formatted(file("b.bin")));
        assertEquals("(#t #u8(0 255 10 7))", out);
        assertEquals(4, Files.size(dir.resolve("b.bin")));
    }

    @Test
    public void fileErrors() {
        assertEquals("(#t #f)(#t #f)", runWithFiles("""
                        (write (guard (e (#t (list (file-error? e) (read-error? e)))) (open-input-file %s)))
                        (write (guard (e (#t (list (file-error? e) (read-error? e)))) (delete-file %s)))""".formatted(file("missing"), file("missing"))));
        // Without I/O access, opening a file is a file error too, not an internal one.
        assertEquals("#t", run("(write (guard (e (#t (file-error? e))) (open-output-file \"/tmp/never-written\")))"));
    }

    @Test
    public void consoleInput() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).in(new ByteArrayInputStream("(1 2) rest\nnext\n".getBytes(StandardCharsets.UTF_8))).out(out).build()) {
            context.eval(SCHEME, "(write (list (read) (read-line) (read-line) (eof-object? (read-char))))");
        }
        assertEquals("((1 2) \" rest\" \"next\" #t)", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    public void errorPort() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).err(err).build()) {
            context.eval(SCHEME, "(display \"to err\" (current-error-port)) (display \"to out\")");
        }
        assertEquals("to out", out.toString(StandardCharsets.UTF_8));
        assertEquals("to err", err.toString(StandardCharsets.UTF_8));
    }
}
