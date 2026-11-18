package scheme.test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessMode;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.FileSystem;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import scheme.runtime.SchemeContext;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SPort;
import scheme.runtime.value.SString;

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

    // ------------------------------------------------------------------------------------------
    // The console output port is buffered (design.md D10, "Standard output").

    /** A stream that keeps the text of each write that reaches it. */
    private static final class CountingStream extends ByteArrayOutputStream {
        final List<String> chunks = new ArrayList<>();

        @Override
        public synchronized void write(int b) {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            chunks.add(new String(b, off, len, StandardCharsets.UTF_8));
            super.write(b, off, len);
        }

        String text() {
            return toString(StandardCharsets.UTF_8);
        }
    }

    /**
     * Standard input that serves {@code text}, and records what standard output holds each time it
     * is read or asked what is available.
     */
    private static final class WatchedInput extends InputStream {
        private final ByteArrayInputStream in;
        private final ByteArrayOutputStream out;
        final List<String> seen = new ArrayList<>();

        WatchedInput(String text, ByteArrayOutputStream out) {
            this.in = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
            this.out = out;
        }

        @Override
        public int read() {
            seen.add(out.toString(StandardCharsets.UTF_8));
            return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            seen.add(out.toString(StandardCharsets.UTF_8));
            return in.read(b, off, len);
        }

        @Override
        public int available() {
            seen.add(out.toString(StandardCharsets.UTF_8));
            return in.available();
        }
    }

    /**
     * Standard output reaches the context's stream in a few large writes, not one per
     * {@code display}, and all of it is there when the evaluation returns.
     */
    @Test
    public void standardOutputIsBuffered() {
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            expected.append("line ").append(i).append('\n');
        }
        CountingStream out = new CountingStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            context.eval(SCHEME, "(let loop ((i 0)) (when (< i 1000) (display \"line \") (display i) (newline) (loop (+ i 1))))");
            assertEquals(expected.toString(), out.text());
            assertTrue("writes: " + out.chunks.size(), out.chunks.size() < 10);
        }
    }

    /** {@code flush-output-port} writes what is buffered at once. */
    @Test
    public void flushOutputPortWritesTheBufferedOutput() {
        CountingStream out = new CountingStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            context.eval(SCHEME, "(display \"a\") (display \"b\") (flush-output-port) (display \"c\") (display \"d\")");
            assertEquals(List.of("ab", "cd"), out.chunks);
        }
    }

    /**
     * A write to the console error port first writes what standard output holds, so the two stay in
     * order where they meet (a terminal, or both redirected to one file).
     */
    @Test
    public void errorPortWritesComeAfterTheOutputBeforeThem() {
        ByteArrayOutputStream both = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(both).err(both).build()) {
            context.eval(SCHEME, """
                            (display "1 out ") (display "2 err " (current-error-port))
                            (display "3 out ") (write-string "4 err " (current-error-port)) (display "5 out")""");
        }
        assertEquals("1 out 2 err 3 out 4 err 5 out", both.toString(StandardCharsets.UTF_8));
    }

    /**
     * Reading the console input port first writes what standard output holds, so a prompt shows
     * before the program waits for its answer; so does asking whether a character is ready.
     */
    @Test
    public void readingTheConsoleShowsTheOutputBeforeIt() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WatchedInput in = new WatchedInput("Ada\n", out);
        try (Context context = Context.newBuilder(SCHEME).in(in).out(out).build()) {
            context.eval(SCHEME, "(display \"name? \") (define name (read-line)) (display (string-append \"hello \" name)) (newline)");
            assertEquals("name? ", in.seen.get(0));
            assertEquals("name? hello Ada\n", out.toString(StandardCharsets.UTF_8));
        }
        ByteArrayOutputStream polled = new ByteArrayOutputStream();
        WatchedInput ready = new WatchedInput("", polled);
        try (Context context = Context.newBuilder(SCHEME).in(ready).out(polled).build()) {
            context.eval(SCHEME, "(display \"ready? \") (char-ready?)");
            assertEquals("ready? ", ready.seen.get(0));
        }
    }

    /** {@code exit} and {@code emergency-exit} write the buffered output before the context exits. */
    @Test
    public void exitWritesTheBufferedOutput() {
        String[][] cases = {
                        {"(display \"out\") (exit 3)", "out", "3"},
                        // emergency-exit runs no after-thunks (R7RS 6.14), but the output is written.
                        {"(dynamic-wind (lambda () (display \"in \")) (lambda () (display \"during\") (emergency-exit 4)) (lambda () (display \" after\")))", "in during", "4"},
        };
        for (String[] c : cases) {
            CountingStream out = new CountingStream();
            try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
                context.eval(SCHEME, c[0]);
                fail(c[0] + " should exit");
            } catch (PolyglotException e) {
                assertTrue(c[0], e.isExit());
                assertEquals(c[0], Integer.parseInt(c[2]), e.getExitStatus());
            }
            assertEquals(c[0], c[1], out.text());
        }
    }

    /**
     * A Scheme procedure called from Java has written its output when the call returns, and so has
     * an evaluation that ends in an error.
     */
    @Test
    public void outputIsWrittenWhenACallFromJavaReturns() {
        CountingStream out = new CountingStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            Value greet = context.eval(SCHEME, "(lambda (name) (display \"hello \") (display name) 42)");
            assertEquals(42, greet.execute("Ada").asInt());
            assertEquals("hello Ada", out.text());
            try {
                context.eval(SCHEME, "(display \" bye\") (car 1)");
                fail("car of a number");
            } catch (PolyglotException e) {
                assertTrue(e.isGuestException());
            }
            assertEquals("hello Ada bye", out.text());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Open output file ports are written out with standard output (design.md D10, "Open file
    // ports").

    /** Text that fills more than one buffer, so a lost tail shows as a truncated file. */
    private static final String LONG_TEXT = "0123456789".repeat(2000) + "end";

    /**
     * A program that opens a textual and a binary file port, writes more than a buffer to each and
     * never closes them, then does {@code ending}.
     */
    private String unclosedPorts(String ending) {
        return """
                        (define text (open-output-file %s))
                        (write-string (make-string 20000 #\\a) text)
                        (write-string "end" text)
                        (define binary (open-binary-output-file %s))
                        (write-bytevector (make-bytevector 20000 7) binary)
                        (write-u8 1 binary) (write-u8 2 binary) (write-u8 3 binary)
                        %s""".formatted(file("text.txt"), file("binary.bin"), ending);
    }

    private void assertPortsWritten(String what) throws IOException {
        assertEquals(what, "a".repeat(20000) + "end", Files.readString(dir.resolve("text.txt")));
        byte[] expected = new byte[20003];
        Arrays.fill(expected, 0, 20000, (byte) 7);
        expected[20000] = 1;
        expected[20001] = 2;
        expected[20002] = 3;
        assertArrayEquals(what, expected, Files.readAllBytes(dir.resolve("binary.bin")));
    }

    /** When the program ends, its file ports hold everything written to them, closed or not. */
    @Test
    public void unclosedFilePortsAreWrittenWhenTheProgramEnds() throws IOException {
        try (Context context = Context.newBuilder(SCHEME).allowIO(IOAccess.ALL).build()) {
            context.eval(SCHEME, unclosedPorts(""));
            assertPortsWritten("before the context closes");
        }
        assertPortsWritten("after the context closes");
    }

    /** So do {@code exit} and {@code emergency-exit} (R7RS 6.14; design.md D10). */
    @Test
    public void unclosedFilePortsAreWrittenOnExit() throws IOException {
        for (String exit : new String[]{"(exit 3)", "(emergency-exit 3)", "(dynamic-wind (lambda () #f) (lambda () (emergency-exit 3)) (lambda () #f))"}) {
            try (Context context = Context.newBuilder(SCHEME).allowIO(IOAccess.ALL).build()) {
                context.eval(SCHEME, unclosedPorts(exit));
                fail(exit + " should exit");
            } catch (PolyglotException e) {
                assertTrue(exit, e.isExit());
                assertEquals(exit, 3, e.getExitStatus());
            }
            assertPortsWritten(exit);
        }
    }

    /** So does an uncaught error, before the context closes (the REPL and embedders go on). */
    @Test
    public void unclosedFilePortsAreWrittenAfterAnUncaughtError() throws IOException {
        try (Context context = Context.newBuilder(SCHEME).allowIO(IOAccess.ALL).build()) {
            try {
                context.eval(SCHEME, unclosedPorts("(car 1)"));
                fail("car of a number");
            } catch (PolyglotException e) {
                assertTrue(e.isGuestException());
            }
            assertPortsWritten("after the error");
            // The ports are still open: what is written next is written out in turn.
            context.eval(SCHEME, "(write-string \"more\" text) (write-u8 4 binary)");
            assertEquals("a".repeat(20000) + "endmore", Files.readString(dir.resolve("text.txt")));
            assertEquals(20004, Files.size(dir.resolve("binary.bin")));
        }
    }

    /**
     * Closing the context closes the file ports the program left open: their descriptors are
     * released at once, not when the ports are collected.
     */
    @Test
    public void closingTheContextClosesTheFilePorts() throws IOException {
        assumeTrue(Files.isDirectory(OPEN_DESCRIPTORS));
        Context context = Context.newBuilder(SCHEME).allowIO(IOAccess.ALL).build();
        context.initialize(SCHEME);
        long before = openDescriptors();
        context.eval(SCHEME, """
                        (define (name i) (string-append %s (number->string i)))
                        (define ports
                          (let loop ((i 0) (ports '()))
                            (if (= i 50)
                                ports
                                (let ((p (if (even? i) (open-output-file (name i)) (open-binary-output-file (name i)))))
                                  (if (even? i) (write-string "text" p) (write-u8 i p))
                                  (loop (+ i 1) (cons p ports))))))""".formatted(file("port-")));
        assertTrue("50 files are open", openDescriptors() >= before + 50);
        context.close();
        long after = openDescriptors();
        assertTrue("open descriptors: " + before + " before, " + after + " after", after < before + 10);
        for (int i = 0; i < 50; i++) {
            Path p = dir.resolve("port-" + i);
            if (i % 2 == 0) {
                assertEquals("text", Files.readString(p));
            } else {
                assertArrayEquals(new byte[]{(byte) i}, Files.readAllBytes(p));
            }
        }
    }

    /** Lists the process's open file descriptors on Linux and macOS. */
    private static final Path OPEN_DESCRIPTORS = Path.of("/dev/fd");

    private static long openDescriptors() throws IOException {
        try (Stream<Path> fds = Files.list(OPEN_DESCRIPTORS)) {
            return fds.count();
        }
    }

    /**
     * A port the program drops without closing it is written out and closed after it is collected:
     * the context tracks it weakly, so it doesn't keep it alive.
     */
    @Test
    public void aDroppedPortIsWrittenWhenItIsCollected() throws Exception {
        try (Context context = Context.newBuilder(SCHEME).allowIO(IOAccess.ALL).build()) {
            context.initialize(SCHEME);
            context.enter();
            try {
                SchemeContext scheme = SchemeContext.get(null);
                Path text = dir.resolve("dropped.txt");
                Path binary = dir.resolve("dropped.bin");
                WeakReference<SPort> textPort = dropTextPort(scheme, text);
                WeakReference<SPort> binaryPort = dropBinaryPort(scheme, binary);
                for (int i = 0; i < 100 && (textPort.get() != null || binaryPort.get() != null); i++) {
                    System.gc();
                }
                assertNull("the text port is collected", textPort.get());
                assertNull("the binary port is collected", binaryPort.get());
                // The collected ports' buffers are written out on the context's thread, the next
                // time the context flushes its ports (or opens one).
                for (int i = 0; i < 200 && scheme.openFiles().size() > 0; i++) {
                    scheme.flushOutput();
                    Thread.sleep(10);
                }
                assertEquals(0, scheme.openFiles().size());
                assertEquals(LONG_TEXT, Files.readString(text));
                assertArrayEquals(LONG_TEXT.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(binary));
            } finally {
                context.leave();
            }
        }
    }

    private static WeakReference<SPort> dropTextPort(SchemeContext scheme, Path path) throws IOException {
        SPort port = scheme.openFiles().textOutput(scheme.getEnv().getPublicTruffleFile(path.toString()).newOutputStream());
        port.write(LONG_TEXT);
        return new WeakReference<>(port);
    }

    private static WeakReference<SPort> dropBinaryPort(SchemeContext scheme, Path path) throws IOException {
        SPort port = scheme.openFiles().binaryOutput(scheme.getEnv().getPublicTruffleFile(path.toString()).newOutputStream());
        byte[] bytes = LONG_TEXT.getBytes(StandardCharsets.UTF_8);
        port.writeBytes(bytes, 0, bytes.length);
        return new WeakReference<>(port);
    }

    /**
     * A port that was closed, then collected, is no longer tracked, and doesn't disturb the flush of
     * the ports still open when the program exits.
     */
    @Test
    public void aClosedAndCollectedPortDoesNotBreakTheExitFlush() throws Exception {
        try (Context context = Context.newBuilder(SCHEME).allowIO(IOAccess.ALL).build()) {
            context.eval(SCHEME, """
                            (let ((p (open-output-file %s))) (write-string "closed" p) (close-port p))
                            (let ((p (open-binary-output-file %s))) (write-u8 9 p) (close-port p))""".formatted(file("closed.txt"), file("closed.bin")));
            for (int i = 0; i < 3; i++) {
                System.gc();
            }
            context.eval(SCHEME, unclosedPorts("(exit 0)"));
            fail("exit");
        } catch (PolyglotException e) {
            assertTrue(e.isExit());
        }
        assertEquals("closed", Files.readString(dir.resolve("closed.txt")));
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(dir.resolve("closed.bin")));
        assertPortsWritten("after exit");
    }

    /**
     * 10,000 ports opened and dropped without closing don't pile up: the context tracks them weakly,
     * so they are collected, and their files written and closed. Every file has its content.
     */
    @Test
    public void droppedPortsDoNotLeak() throws Exception {
        int chunks = 10;
        int perChunk = 1000;
        try (Context context = Context.newBuilder(SCHEME).allowIO(IOAccess.ALL).build()) {
            Value openMany = context.eval(SCHEME, """
                            (lambda (from to)
                              (do ((i from (+ i 1))) ((= i to))
                                (let ((name (string-append %s (number->string i))))
                                  (if (even? i)
                                      (write-string (number->string i) (open-output-file name))
                                      (write-bytevector (string->utf8 (number->string i)) (open-binary-output-file name))))))""".formatted(file("many-")));
            int most = 0;
            for (int c = 0; c < chunks; c++) {
                openMany.execute(c * perChunk, (c + 1) * perChunk);
                System.gc();
                most = Math.max(most, trackedPorts(context));
            }
            assertTrue("ports tracked at most: " + most, most < chunks * perChunk);
            int tracked = trackedPorts(context);
            for (int i = 0; i < 200 && tracked > 0; i++) {
                System.gc();
                Thread.sleep(10);
                tracked = trackedPorts(context);
            }
            assertEquals("ports still tracked", 0, tracked);
        }
        for (int i = 0; i < chunks * perChunk; i++) {
            assertEquals(Integer.toString(i), Files.readString(dir.resolve("many-" + i)));
        }
    }

    /** The ports the context tracks, once it has written out (and dropped) the collected ones. */
    private static int trackedPorts(Context context) {
        context.enter();
        try {
            SchemeContext scheme = SchemeContext.get(null);
            scheme.flushOutput();
            return scheme.openFiles().size();
        } finally {
            context.leave();
        }
    }

    /**
     * Closing the context closes the input file ports the program left open, textual and binary,
     * as it does output ones: their descriptors are released at once, not when the ports are
     * collected.
     */
    @Test
    public void closingTheContextClosesTheInputFilePorts() throws IOException {
        assumeTrue(Files.isDirectory(OPEN_DESCRIPTORS));
        for (int i = 0; i < 50; i++) {
            Files.writeString(dir.resolve("in-" + i), "x" + i);
        }
        Context context = Context.newBuilder(SCHEME).allowIO(IOAccess.ALL).build();
        context.initialize(SCHEME);
        long before = openDescriptors();
        String read = context.eval(SCHEME, """
                        (define (name i) (string-append %s (number->string i)))
                        (define ports
                          (let loop ((i 0) (ports '()))
                            (if (= i 50)
                                ports
                                (let ((p (if (even? i) (open-input-file (name i)) (open-binary-input-file (name i)))))
                                  (if (even? i) (read-char p) (read-u8 p))
                                  (loop (+ i 1) (cons p ports))))))
                        (length ports)""".formatted(file("in-"))).toString();
        assertEquals("50", read);
        assertTrue("50 files are open", openDescriptors() >= before + 50);
        context.close();
        long after = openDescriptors();
        assertTrue("open descriptors: " + before + " before, " + after + " after", after < before + 10);
    }

    /**
     * The same through the context's file system: every file a port opened, input or output, is
     * closed when the context closes, whether the program kept its port, dropped it or closed it.
     */
    @Test
    public void closingTheContextClosesEveryFileItsPortsOpened() throws IOException {
        Files.writeString(dir.resolve("in.txt"), "text");
        Files.write(dir.resolve("in.bin"), new byte[]{1, 2, 3});
        CountingFileSystem fs = new CountingFileSystem();
        String out = runCountingReads(fs, """
                        (define kept (list (open-input-file %s) (open-binary-input-file %s)
                                           (open-output-file %s) (open-binary-output-file %s)))
                        (read-char (open-input-file %s))
                        (read-u8 (open-binary-input-file %s))
                        (close-port (open-input-file %s))
                        (write (list (read-char (car kept)) (read-u8 (cadr kept))))""".formatted(file("in.txt"), file("in.bin"),
                        file("out.txt"), file("out.bin"), file("in.txt"), file("in.bin"), file("in.txt")));
        assertEquals("(#\\t 1)", out);
        assertEquals("files opened", 7, fs.opened.get());
        assertEquals("files closed", 7, fs.closed.get());
    }

    /**
     * An input file port the program drops without closing it is closed after it is collected, the
     * next time the context opens a file port or flushes them; one it keeps stays tracked, and
     * open. Closing a port stops tracking it.
     */
    @Test
    public void droppedInputPortsAreClosedWhenCollected() throws Exception {
        Files.writeString(dir.resolve("in.txt"), "text");
        Files.write(dir.resolve("in.bin"), new byte[]{1, 2, 3});
        try (Context context = Context.newBuilder(SCHEME).allowIO(IOAccess.ALL).build()) {
            context.eval(SCHEME, """
                            (define kept-text (open-input-file %s))
                            (define kept-binary (open-binary-input-file %s))
                            (let ((p (open-input-file %s))) (read-char p) (close-port p))
                            (do ((i 0 (+ i 1))) ((= i 1000))
                              (if (even? i)
                                  (read-char (open-input-file %s))
                                  (read-u8 (open-binary-input-file %s))))""".formatted(file("in.txt"), file("in.bin"), file("in.txt"),
                            file("in.txt"), file("in.bin")));
            int tracked = trackedPorts(context);
            assertTrue("ports tracked: " + tracked, tracked >= 2);
            for (int i = 0; i < 200 && tracked > 2; i++) {
                System.gc();
                Thread.sleep(10);
                tracked = trackedPorts(context);
            }
            assertEquals("ports still tracked", 2, tracked);
            assertEquals("(\"text\" #u8(1 2 3))", context.eval(SCHEME, """
                            (let ((r (list (read-string 10 kept-text) (read-bytevector 10 kept-binary))))
                              (close-port kept-text)
                              (close-port kept-binary)
                              r)""").toString());
            assertEquals("closed ports are not tracked", 0, trackedPorts(context));
        }
    }

    /**
     * Binary file output ports are buffered, as textual ones are: their file holds nothing until
     * {@code flush-output-port} (or closing the port) writes the buffer out.
     */
    @Test
    public void binaryFilePortsAreBuffered() {
        String out = runWithFiles("""
                        (define f %s)
                        (define (contents) (call-with-port (open-binary-input-file f) (lambda (p) (read-bytevector 100 p))))
                        (define p (open-binary-output-file f))
                        (write-u8 1 p) (write-bytevector #u8(2 3) p)
                        (write (contents))
                        (flush-output-port p)
                        (write (contents))
                        (write-u8 4 p)
                        (write (contents))
                        (close-port p)
                        (write (contents))""".formatted(file("buffered.bin")));
        assertEquals("#<eof>#u8(1 2 3)#u8(1 2 3)#u8(1 2 3 4)", out);
    }

    /**
     * The default file system, counting the reads made on the channels it opens: a buffered port
     * reads its file a buffer at a time, an unbuffered one a byte at a time.
     */
    private static final class CountingFileSystem implements FileSystem {
        private final FileSystem fs = FileSystem.newDefaultFileSystem();
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger opened = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();

        @Override
        public Path parsePath(URI uri) {
            return fs.parsePath(uri);
        }

        @Override
        public Path parsePath(String path) {
            return fs.parsePath(path);
        }

        @Override
        public void checkAccess(Path path, Set<? extends AccessMode> modes, LinkOption... options) throws IOException {
            fs.checkAccess(path, modes, options);
        }

        @Override
        public void createDirectory(Path path, FileAttribute<?>... attrs) throws IOException {
            fs.createDirectory(path, attrs);
        }

        @Override
        public void delete(Path path) throws IOException {
            fs.delete(path);
        }

        @Override
        public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
            SeekableByteChannel channel = fs.newByteChannel(path, options, attrs);
            opened.incrementAndGet();
            return new SeekableByteChannel() {
                private boolean counted;

                @Override
                public int read(ByteBuffer dst) throws IOException {
                    reads.incrementAndGet();
                    return channel.read(dst);
                }

                @Override
                public int write(ByteBuffer src) throws IOException {
                    return channel.write(src);
                }

                @Override
                public long position() throws IOException {
                    return channel.position();
                }

                @Override
                public SeekableByteChannel position(long newPosition) throws IOException {
                    channel.position(newPosition);
                    return this;
                }

                @Override
                public long size() throws IOException {
                    return channel.size();
                }

                @Override
                public SeekableByteChannel truncate(long size) throws IOException {
                    channel.truncate(size);
                    return this;
                }

                @Override
                public boolean isOpen() {
                    return channel.isOpen();
                }

                @Override
                public void close() throws IOException {
                    if (!counted) {
                        counted = true;
                        closed.incrementAndGet();
                    }
                    channel.close();
                }
            };
        }

        @Override
        public DirectoryStream<Path> newDirectoryStream(Path dirPath, DirectoryStream.Filter<? super Path> filter) throws IOException {
            return fs.newDirectoryStream(dirPath, filter);
        }

        @Override
        public Path toAbsolutePath(Path path) {
            return fs.toAbsolutePath(path);
        }

        @Override
        public Path toRealPath(Path path, LinkOption... options) throws IOException {
            return fs.toRealPath(path, options);
        }

        @Override
        public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
            return fs.readAttributes(path, attributes, options);
        }
    }

    /** Runs a program in a context whose file system counts the reads made on its files. */
    private static String runCountingReads(CountingFileSystem fs, String program) {
        return run(Context.newBuilder(SCHEME).allowIO(IOAccess.newBuilder().fileSystem(fs).build()), program);
    }

    /**
     * Binary file input ports read their file through an 8 KB buffer: reading 20,000 bytes with
     * {@code read-u8} (and {@code peek-u8} before each) reads the file a few times, not once per
     * byte (design.md D10).
     */
    @Test
    public void binaryFileInputIsBuffered() throws IOException {
        Files.write(dir.resolve("in.bin"), new byte[20000]);
        CountingFileSystem fs = new CountingFileSystem();
        String out = runCountingReads(fs, """
                        (define p (open-binary-input-file %s))
                        (let loop ((n 0))
                          (peek-u8 p)
                          (if (eof-object? (read-u8 p)) (write n) (loop (+ n 1))))
                        (close-port p)""".formatted(file("in.bin")));
        assertEquals("20000", out);
        assertTrue("reads of the file: " + fs.reads.get(), fs.reads.get() <= 5);
    }

    /** Textual file input ports read their file through a buffer already (a BufferedReader). */
    @Test
    public void textualFileInputIsBuffered() throws IOException {
        Files.writeString(dir.resolve("in.txt"), "a".repeat(20000));
        CountingFileSystem fs = new CountingFileSystem();
        String out = runCountingReads(fs, """
                        (define p (open-input-file %s))
                        (let loop ((n 0))
                          (peek-char p)
                          (if (eof-object? (read-char p)) (write n) (loop (+ n 1))))
                        (close-port p)""".formatted(file("in.txt")));
        assertEquals("20000", out);
        assertTrue("reads of the file: " + fs.reads.get(), fs.reads.get() <= 5);
    }

    /**
     * {@code read-u8}, {@code peek-u8}, {@code u8-ready?}, {@code read-bytevector} and
     * {@code read-bytevector!} mixed, each across a refill of the port's 8 KB buffer, up to and past
     * the end of the file. The file's byte {@code i} is {@code expected}'s, a pattern 8 KB doesn't
     * repeat.
     */
    @Test
    public void binaryFileInputAcrossBufferBoundaries() throws IOException {
        int size = 2 * 8192 + 100;
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (i * 7 + i / 251);
        }
        Files.write(dir.resolve("pattern.bin"), bytes);
        String out = runWithFiles("""
                        (define (expected i) (modulo (+ (* i 7) (quotient i 251)) 256))
                        (define p (open-binary-input-file %s))
                        (define pos 0)
                        (define wrong '())
                        (define (check-byte b)
                          (unless (eqv? b (expected pos)) (set! wrong (cons pos wrong)))
                          (set! pos (+ pos 1)))
                        (define (check-bytes bv start end)
                          (do ((i start (+ i 1))) ((= i end)) (check-byte (bytevector-u8-ref bv i))))
                        (define results '())
                        (define (note x) (set! results (cons x results)))
                        ;; All but the first buffer's last two bytes.
                        (let ((bv (read-bytevector 8190 p))) (note (bytevector-length bv)) (check-bytes bv 0 8190))
                        (note (u8-ready? p))
                        ;; Peeks and reads over the first refill, at 8192.
                        (do () ((= pos 8195))
                          (let* ((a (peek-u8 p)) (b (peek-u8 p)) (c (read-u8 p)))
                            (unless (and (eqv? a b) (eqv? b c)) (set! wrong (cons (list 'peek pos) wrong)))
                            (check-byte c)))
                        (note (u8-ready? p))
                        ;; Into the middle of a bytevector, over the second refill, at 16384.
                        (let* ((bv (make-bytevector 8500 0)) (n (read-bytevector! bv p 100 8300)))
                          (note n)
                          (check-bytes bv 100 8300)
                          (note (list (bytevector-u8-ref bv 99) (bytevector-u8-ref bv 8300))))
                        ;; A peek after a bulk read, then more than is left.
                        (note (eqv? (peek-u8 p) (expected pos)))
                        (let ((bv (read-bytevector 1000 p))) (note (bytevector-length bv)) (check-bytes bv 0 (bytevector-length bv)))
                        ;; The end of the file, every way.
                        (note (u8-ready? p))
                        (note (list (eof-object? (peek-u8 p)) (eof-object? (read-u8 p)) (eof-object? (read-u8 p))
                                    (eof-object? (read-bytevector 10 p)) (eof-object? (read-bytevector! (make-bytevector 4) p))
                                    (read-bytevector 0 p)))
                        (close-port p)
                        (write (list pos (reverse wrong) (reverse results)))""".formatted(file("pattern.bin")));
        assertEquals("(16484 () (8190 #t #t 8200 (0 0) #t 89 #t (#t #t #t #t #t #u8())))", out);
    }

    /** An empty file is at its end from the start. */
    @Test
    public void emptyBinaryFile() throws IOException {
        Files.write(dir.resolve("empty.bin"), new byte[0]);
        String out = runWithFiles("""
                        (define p (open-binary-input-file %s))
                        (write (list (u8-ready? p) (eof-object? (peek-u8 p)) (eof-object? (read-u8 p))
                                     (eof-object? (read-bytevector 1 p)) (eof-object? (read-bytevector! (make-bytevector 1) p))))
                        (close-port p)""".formatted(file("empty.bin")));
        assertEquals("(#t #t #t #t #t)", out);
    }

    private static String md5(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
    }

    /**
     * A 1 MB file read byte by byte (with a peek every 1,000 bytes), in 1,000-byte
     * {@code read-bytevector}s and in 3,000-byte {@code read-bytevector!}s, each copied to a file:
     * every copy has the file's MD5.
     */
    @Test
    public void largeBinaryFileReadsMatchItsContents() throws Exception {
        byte[] bytes = new byte[(1 << 20) + 17];
        new Random(199).nextBytes(bytes);
        Files.write(dir.resolve("large.bin"), bytes);
        String out = runWithFiles("""
                        (define in %s)
                        (call-with-port (open-binary-input-file in)
                          (lambda (p)
                            (call-with-port (open-binary-output-file %s)
                              (lambda (o)
                                (let loop ((i 0))
                                  (when (= 0 (modulo i 1000)) (peek-u8 p))
                                  (let ((b (read-u8 p)))
                                    (unless (eof-object? b) (write-u8 b o) (loop (+ i 1)))))))))
                        (call-with-port (open-binary-input-file in)
                          (lambda (p)
                            (call-with-port (open-binary-output-file %s)
                              (lambda (o)
                                (let loop ()
                                  (let ((bv (read-bytevector 1000 p)))
                                    (unless (eof-object? bv) (write-bytevector bv o) (loop))))))))
                        (call-with-port (open-binary-input-file in)
                          (lambda (p)
                            (call-with-port (open-binary-output-file %s)
                              (lambda (o)
                                (let ((bv (make-bytevector 3000)))
                                  (let loop ()
                                    (let ((n (read-bytevector! bv p)))
                                      (unless (eof-object? n) (write-bytevector bv o 0 n) (loop)))))))))
                        (write 'done)""".formatted(file("large.bin"), file("bytes.bin"), file("chunks.bin"), file("into.bin")));
        assertEquals("done", out);
        String expected = md5(bytes);
        for (String copy : new String[]{"bytes.bin", "chunks.bin", "into.bin"}) {
            assertEquals(copy, expected, md5(Files.readAllBytes(dir.resolve(copy))));
        }
    }

    /** A stream of {@code bytes} that counts its one-byte and its bulk reads. */
    private static final class CountingInputStream extends ByteArrayInputStream {
        int singleReads;
        int bulkReads;

        CountingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public synchronized int read() {
            singleReads++;
            return super.read();
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) {
            bulkReads++;
            return super.read(b, off, len);
        }
    }

    /**
     * A reader of {@code text} that counts its one-character and its bulk reads, and returns at most
     * {@code most} units from a bulk read (a file's decoder or a pipe returns fewer than asked for).
     */
    private static final class CountingReader extends java.io.Reader {
        private final String text;
        private final int most;
        private int pos;
        private int mark;
        int singleReads;
        int bulkReads;

        CountingReader(String text) {
            this(text, Integer.MAX_VALUE);
        }

        CountingReader(String text, int most) {
            this.text = text;
            this.most = most;
        }

        @Override
        public int read() {
            singleReads++;
            return pos < text.length() ? text.charAt(pos++) : -1;
        }

        @Override
        public int read(char[] cbuf, int off, int len) {
            bulkReads++;
            if (pos == text.length()) {
                return -1;
            }
            int n = Math.min(Math.min(len, most), text.length() - pos);
            text.getChars(pos, pos + n, cbuf, off);
            pos += n;
            return n;
        }

        @Override
        public boolean markSupported() {
            return true;
        }

        @Override
        public void mark(int readAheadLimit) {
            mark = pos;
        }

        @Override
        public void reset() {
            pos = mark;
        }

        @Override
        public void close() {
        }
    }

    /**
     * A binary input port's bulk reads ({@code read-bytevector}, {@code read-bytevector!}) copy
     * from its stream in bulk, not a byte at a time, and a byte peeked before one comes first.
     */
    @Test
    public void bulkByteReadsCopyFromTheStream() {
        byte[] bytes = new byte[100_000];
        new Random(5).nextBytes(bytes);
        CountingInputStream in = new CountingInputStream(bytes);
        SPort port = SPort.binaryInput(in);
        assertArrayEquals(Arrays.copyOfRange(bytes, 0, 10), port.readBytes(10));
        assertEquals(bytes[10] & 0xff, port.peekByte());
        byte[] into = new byte[50_000];
        assertEquals(40_000, port.readBytes(into, 5, 40_000));
        assertArrayEquals(Arrays.copyOfRange(bytes, 10, 40_010), Arrays.copyOfRange(into, 5, 40_005));
        assertEquals(0, into[4]);
        assertEquals(0, into[40_005]);
        assertEquals(0, port.readBytes(into, 0, 0));
        assertArrayEquals(new byte[0], port.readBytes(0));
        // More than is left: the rest.
        assertArrayEquals(Arrays.copyOfRange(bytes, 40_010, 100_000), port.readBytes(Integer.MAX_VALUE));
        assertNull(port.readBytes(1));
        assertEquals(-1, port.readBytes(into, 0, 4));
        assertArrayEquals(new byte[0], port.readBytes(0));
        assertEquals(-1, port.peekByte());
        assertNull(port.readBytes(1));
        assertEquals("one-byte reads: the peeks", 2, in.singleReads);
        assertTrue("bulk reads: " + in.bulkReads, in.bulkReads <= 20);
    }

    /**
     * A textual input port's {@code read-string} copies from its reader in bulk, and counts in
     * characters (code points): a surrogate pair split at the end of a bulk read is read whole.
     */
    @Test
    public void bulkStringReadsCopyFromTheReader() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30_000; i++) {
            sb.appendCodePoint(switch (i % 5) {
                case 0 -> 'a';
                case 1 -> 0x1F600;
                case 2 -> 0xE9;
                case 3 -> 0x4E2D;
                default -> '\n';
            });
        }
        String text = sb.toString();
        int[] cps = text.codePoints().toArray();
        CountingReader in = new CountingReader(text);
        SPort port = SPort.textInput(in);
        int pos = 0;
        StringBuilder read = new StringBuilder();
        // Chunk sizes that end on either half of a pair, and a peek between some.
        for (int k : new int[]{1, 2, 3, 4, 5, 7, 8191, 8192, 8193, 1, 2}) {
            String s = assertReadString("read-string " + k, new String(cps, pos, k), port.readString(k));
            assertEquals("characters read for " + k, k, s.codePointCount(0, s.length()));
            pos += k;
            read.append(s);
            if (k > 4) {
                assertEquals(cps[pos], port.peekChar());
            }
        }
        assertReadString("read-string 0", "", port.readString(0));
        String rest = assertReadString("the rest", new String(cps, pos, cps.length - pos), port.readString(Integer.MAX_VALUE));
        assertEquals(cps.length - pos, rest.codePointCount(0, rest.length()));
        read.append(rest);
        assertEquals(text, read.toString());
        assertReadString("at the end", null, port.readString(1));
        assertReadString("0 at the end", "", port.readString(0));
        assertEquals(-1, port.peekChar());
        assertReadString("after a peek at the end", null, port.readString(5));
        assertTrue("one-character reads: " + in.singleReads, in.singleReads <= 20);
        // 36,000 UTF-16 units. A read-string asks for as many units as characters are still wanted,
        // so when pairs make a read come up short it asks again for the rest: a few reads each.
        assertTrue("bulk reads: " + in.bulkReads, in.bulkReads <= 100);
    }

    /**
     * What a textual input port's {@code read-string} returns: the eof object if {@code expected}
     * is null, else a new mutable {@link SString} of {@code expected}'s characters (code points),
     * Latin-1 exactly when every one of them is at most 255 (design.md D1). Returns its text.
     */
    private static String assertReadString(String what, String expected, Object read) {
        if (expected == null) {
            assertEquals(what, SConstant.EOF, read);
            return null;
        }
        assertTrue(what + ": " + read, read instanceof SString);
        SString s = (SString) read;
        assertEquals(what, expected, s.toString());
        assertEquals(what + ": characters", expected.codePointCount(0, expected.length()), s.length());
        for (int i = 0, j = 0; i < s.length(); i++) {
            int c = expected.codePointAt(j);
            assertEquals(what + ": character " + i, c, s.codePointAt(i));
            j += Character.charCount(c);
        }
        assertEquals(what + ": Latin-1", expected.chars().allMatch(c -> c <= 0xff), s.isLatin1());
        assertFalse(what + ": mutable", s.isImmutable());
        return s.toString();
    }

    /**
     * A textual input port's {@code read-string} builds the string from what its reader reads, with
     * no Java {@code String} between, and reads what reading a character at a time did (the
     * {@link LineModel}): Latin-1 and wide text, {@code k} counting characters (code points), a
     * surrogate pair split by the end of a reader's read or of a chunk (8192 units) read whole, a
     * lone surrogate kept as it is, a peeked character first, and the eof object at the end.
     */
    @Test
    public void readStringBuildsTheStringFromTheReadersText() {
        Random random = new Random(23);
        String latin1 = "aé\u00ff\u0000\u007f\u0080".repeat(3000);
        String[] texts = {"", "a", "é", "\u00ff\u0100", "😀", "\uD83D", "\uDE00", "a\uD83Dé", "\uDE00\uD83D", "😀\uD83D", latin1, latin1 + "中",
                        latin1 + "😀", "中" + latin1, "a" + "😀".repeat(9000), "😀".repeat(9000), mixed(random, 30_000), mixed(random, 30_000)};
        int[] mosts = {1, 2, 3, 7, 4095, 8191, 8192, 8193, Integer.MAX_VALUE};
        for (String text : texts) {
            int[] cps = text.codePoints().toArray();
            for (int most : mosts) {
                String what = text.length() + " units, at most " + most + " a read";
                // Whole, then the end.
                SPort port = SPort.textInput(new CountingReader(text, most));
                assertReadString(what, cps.length == 0 ? null : text, port.readString(Integer.MAX_VALUE));
                assertReadString(what + ", at the end", null, port.readString(Integer.MAX_VALUE));
                assertReadString(what + ", 0 at the end", "", port.readString(0));
                // Exactly as many characters as there are: no eof yet.
                port = SPort.textInput(new CountingReader(text, most));
                assertReadString(what + ", all " + cps.length, cps.length == 0 ? null : text, port.readString(Math.max(1, cps.length)));
                assertEquals(-1, port.peekChar());
                assertReadString(what + ", after a peek at the end", null, port.readString(1));
                // Chunks of random sizes, some across 8192 units, with peeks and reads between.
                port = SPort.textInput(new CountingReader(text, most));
                LineModel model = new LineModel(text);
                while (model.pos < cps.length) {
                    int op = random.nextInt(8);
                    if (op == 0) {
                        assertEquals(what, model.peek(), port.peekChar());
                    } else if (op == 1) {
                        assertEquals(what, model.read(), port.readChar());
                    } else {
                        int k = op == 2 ? 8190 + random.nextInt(5) : random.nextInt(op == 3 ? 4 : 2000);
                        assertReadString(what + ", " + k + " at " + model.pos, model.readString(k), port.readString(k));
                    }
                }
                assertReadString(what + ", at the end", null, port.readString(3));
            }
        }
    }

    /**
     * {@code n} random characters: ASCII, Latin-1, a line ending, CJK, a pair and both halves of
     * one alone (which pair up when a lone high one comes right before a lone low one).
     */
    private static String mixed(Random random, int n) {
        int[] chars = {'a', 'z', 0xe9, 0xff, '\n', 0x4e2d, 0x1F600, 0xD83D, 0xDE00};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            // Mostly Latin-1, so a wide one comes after a long Latin-1 run.
            int c = chars[random.nextInt(10) < 8 ? random.nextInt(5) : 5 + random.nextInt(4)];
            sb.append(Character.toChars(c));
        }
        return sb.toString();
    }

    /**
     * A textual input port's {@code read-line} finds the line ending, LF, CR LF or a lone CR, in
     * the reader's buffer, not a character at a time, and reads exactly what reading a character at
     * a time did (the {@link LineModel}): a last line without an ending, empty lines, CR LF and CR
     * split across the buffer's ends, lines longer than the buffer, surrogate pairs, and a peeked
     * character (a CR or LF among them) before a line, over a buffered reader (files' and the
     * console's until they decoded UTF-8 themselves), a string port and another reader that can
     * mark.
     */
    @Test
    public void readLineFindsTheLineEndingInTheBuffer() throws IOException {
        Random random = new Random(11);
        String[] texts = {"", "\n", "\r", "\r\n", "\n\r", "\r\r\n\n", "a", "a\n", "a\r", "a\r\n", "😀", "é\r\n中", lines(random, 300, true),
                        lines(random, 300, false), lines(random, 3000, true)};
        for (String text : texts) {
            for (int size : new int[]{1, 2, 3, 7, 64, 8192}) {
                readLines(text, SPort.textInput(new BufferedReader(new CountingReader(text), size)), random);
            }
            readLines(text, SPort.inputString(text), random);
            readLines(text, SPort.textInput(new CountingReader(text)), random);
            // Only read-line: the reader is read in bulk.
            CountingReader in = new CountingReader(text);
            List<String> expected = new LineModel(text).lines();
            SPort port = SPort.textInput(in);
            for (String line : expected) {
                assertEquals(line, port.readLine().toString());
            }
            assertEquals(SConstant.EOF, port.readLine());
            assertTrue("one-character reads: " + in.singleReads + " for " + text.length(), in.singleReads <= text.length() / 20 + 1);
            assertTrue("bulk reads: " + in.bulkReads + " for " + expected.size(), in.bulkReads <= 3 * expected.size() + text.length() / 1000 + 2);
        }
    }

    /** {@code n} lines of random lengths and characters, each with a random line ending. */
    private static String lines(Random random, int n, boolean lastEnds) {
        int[] chars = {'a', ' ', 0xe9, 0xff, 0x4e2d, 0x1F600};
        String[] endings = {"\n", "\r\n", "\r"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            int length = switch (random.nextInt(10)) {
                case 0 -> 0;
                case 1 -> random.nextInt(20_000);
                default -> random.nextInt(200);
            };
            int[] alphabet = Arrays.copyOf(chars, 1 + random.nextInt(chars.length));
            for (int j = 0; j < length; j++) {
                sb.appendCodePoint(alphabet[random.nextInt(alphabet.length)]);
            }
            if (i < n - 1 || lastEnds) {
                sb.append(endings[random.nextInt(endings.length)]);
            }
        }
        return sb.toString();
    }

    /** Reads {@code text} from {@code port} with read-line, mixed with the other reads, against the model. */
    private static void readLines(String text, SPort port, Random random) {
        LineModel model = new LineModel(text);
        while (true) {
            int op = random.nextInt(10);
            String what = op + " at " + model.pos + " of " + text.length();
            if (op == 0) {
                assertEquals(what, model.peek(), port.peekChar());
            } else if (op == 1) {
                assertEquals(what, model.read(), port.readChar());
            } else if (op == 2) {
                int k = random.nextInt(5);
                assertReadString(what, model.readString(k), port.readString(k));
            } else {
                String line = model.readLine();
                Object read = port.readLine();
                assertEquals(what, line == null ? SConstant.EOF : line, line == null ? read : read.toString());
                if (line == null) {
                    break;
                }
            }
        }
        assertEquals(-1, port.peekChar());
        assertEquals(SConstant.EOF, port.readLine());
    }

    /** A textual input port as read-line read it before it read in bulk: a character at a time. */
    private static final class LineModel {
        private final int[] cps;
        int pos;

        LineModel(String text) {
            cps = text.codePoints().toArray();
        }

        int peek() {
            return pos < cps.length ? cps[pos] : -1;
        }

        int read() {
            int c = peek();
            pos = Math.min(pos + 1, cps.length);
            return c;
        }

        String readString(int k) {
            if (k > 0 && pos == cps.length) {
                return null;
            }
            int n = Math.min(k, cps.length - pos);
            pos += n;
            return new String(cps, pos - n, n);
        }

        String readLine() {
            int c = read();
            if (c < 0) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            while (c >= 0 && c != '\n') {
                if (c == '\r') {
                    if (peek() == '\n') {
                        read();
                    }
                    break;
                }
                sb.appendCodePoint(c);
                c = read();
            }
            return sb.toString();
        }

        List<String> lines() {
            List<String> lines = new ArrayList<>();
            for (String line = readLine(); line != null; line = readLine()) {
                lines.add(line);
            }
            return lines;
        }
    }

    /**
     * A stream of {@code bytes} whose bulk reads return at most {@code most} bytes (a file's stream
     * can return fewer than asked for), and that counts its one-byte and bulk reads.
     */
    private static final class ShortReadStream extends InputStream {
        private final byte[] bytes;
        private final int most;
        private int pos;
        int singleReads;
        int bulkReads;

        ShortReadStream(byte[] bytes, int most) {
            this.bytes = bytes;
            this.most = most;
        }

        @Override
        public int read() {
            singleReads++;
            return pos < bytes.length ? bytes[pos++] & 0xff : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            bulkReads++;
            if (len == 0) {
                return 0;
            }
            if (pos == bytes.length) {
                return -1;
            }
            int n = Math.min(Math.min(len, most), bytes.length - pos);
            System.arraycopy(bytes, pos, b, off, n);
            pos += n;
            return n;
        }

        @Override
        public int available() {
            return bytes.length - pos;
        }
    }

    /**
     * What a textual input file port read {@code bytes} as until it decoded UTF-8 itself (M11-06):
     * a port over the JDK's decoder, as {@code TruffleFile.newBufferedReader(UTF_8)} gave it.
     */
    private static SPort jdkDecodedPort(byte[] bytes) {
        return SPort.textInput(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
    }

    /** {@code bytes} decoded as the JDK's decoder decodes a stream of them, malformed input included. */
    private static String jdkDecoded(byte[] bytes) throws IOException {
        java.io.StringWriter text = new java.io.StringWriter();
        try (java.io.Reader in = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            in.transferTo(text);
        }
        return text.toString();
    }

    /**
     * Byte strings for the UTF-8 port to decode: valid UTF-8 of every length of sequence, and
     * malformed input of every kind the JDK's decoder replaces (a continuation byte or a byte that
     * starts nothing, C0/C1 and F5-FF, an overlong or cut sequence, an encoded surrogate, one above
     * U+10FFFF), alone, at the end, and at random among valid text.
     */
    private static List<byte[]> utf8Samples(Random random) {
        List<byte[]> samples = new ArrayList<>();
        int[][] fixed = {{}, {'a'}, {0xC3, 0xA9}, {0xE4, 0xB8, 0xAD}, {0xF0, 0x9F, 0x98, 0x80}, {0x80}, {0xBF}, {0xC0, 0xAF}, {0xC1, 0xBF},
                        {0xC3}, {0xC3, 'a'}, {0xE4, 0xB8}, {0xE4, 0xB8, 'a'}, {0xE4, 'a'}, {0xE0, 0x80, 0x80}, {0xE0, 0x9F, 0xBF},
                        {0xED, 0xA0, 0x80}, {0xED, 0xBF, 0xBF}, {0xED, 0x9F, 0xBF}, {0xEF, 0xBF, 0xBF}, {0xEF, 0xBB, 0xBF, 'a'},
                        {0xF0, 0x9F, 0x98}, {0xF0, 0x9F}, {0xF0}, {0xF0, 0x9F, 'a'}, {0xF0, 0x9F, 0x98, 'a'}, {0xF0, 0x80, 0x80, 0x80},
                        {0xF0, 0x8F, 0xBF, 0xBF}, {0xF4, 0x8F, 0xBF, 0xBF}, {0xF4, 0x90, 0x80, 0x80}, {0xF5, 0x80, 0x80, 0x80},
                        {0xF7, 0xBF, 0xBF, 0xBF}, {0xF8, 0x88, 0x80, 0x80, 0x80}, {0xFE}, {0xFF, 'a'}, {'\r'}, {0xC3, '\r', '\n'},
                        {0xE4, 0xB8, '\n'}, {0xF0, 0x9F, 0x98, '\r'}};
        for (int[] f : fixed) {
            byte[] b = new byte[f.length];
            for (int i = 0; i < f.length; i++) {
                b[i] = (byte) f[i];
            }
            samples.add(b);
        }
        samples.add(lines(random, 300, true).getBytes(StandardCharsets.UTF_8));
        samples.add(lines(random, 100, false).getBytes(StandardCharsets.UTF_8));
        samples.add(mixed(random, 30_000).getBytes(StandardCharsets.UTF_8));
        samples.add(("aéÿ".repeat(3000) + "中😀").getBytes(StandardCharsets.UTF_8));
        // Valid text with malformed bytes at random: a few, and many.
        for (int density : new int[]{2000, 50, 3}) {
            byte[] text = lines(random, 200, random.nextBoolean()).getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < text.length; i++) {
                if (random.nextInt(density) == 0) {
                    text[i] = (byte) (switch (random.nextInt(5)) {
                        case 0 -> 0x80 + random.nextInt(64);
                        case 1 -> 0xC0 + random.nextInt(32);
                        case 2 -> 0xE0 + random.nextInt(16);
                        case 3 -> 0xF0 + random.nextInt(16);
                        default -> random.nextInt(256);
                    });
                }
            }
            samples.add(text);
        }
        // Random bytes, mostly leads and continuations.
        byte[] noise = new byte[20_000];
        for (int i = 0; i < noise.length; i++) {
            int kind = random.nextInt(6);
            noise[i] = (byte) (kind == 0 ? random.nextInt(128) : kind == 1 ? '\n' : kind == 2 ? 0x80 + random.nextInt(64) : 0xC0 + random.nextInt(64));
        }
        samples.add(noise);
        return samples;
    }

    /**
     * A textual input file port decodes UTF-8 itself (design.md D10, M11-06) and reads exactly what
     * it read through the JDK's decoder: the same characters, malformed input replaced the same way,
     * the same strings (Latin-1 storage included) from {@code read-string} and {@code read-line}, and
     * the same eof objects, with the same random mix of reads on both ports, from a stream that
     * returns at most 1, 2, 3, 5, 4095, 8191, 8192, 8193 or any number of bytes a read, so that
     * every sequence is cut by a read's end somewhere.
     */
    @Test
    public void utf8InputReadsWhatTheJdkDecoderRead() throws IOException {
        Random random = new Random(31);
        int[] mosts = {1, 2, 3, 5, 4095, 8191, 8192, 8193, Integer.MAX_VALUE};
        for (byte[] bytes : utf8Samples(random)) {
            String text = jdkDecoded(bytes);
            for (int most : mosts) {
                String what = bytes.length + " bytes, at most " + most + " a read";
                // Whole, then the end.
                SPort port = SPort.utf8Input(new ShortReadStream(bytes, most));
                assertReadString(what, text.isEmpty() ? null : text, port.readString(Integer.MAX_VALUE));
                assertReadString(what + ", at the end", null, port.readString(1));
                assertEquals(what, -1, port.peekChar());
                assertEquals(what, SConstant.EOF, port.readLine());
                // Only read-line, then only read-char.
                port = SPort.utf8Input(new ShortReadStream(bytes, most));
                SPort jdk = jdkDecodedPort(bytes);
                for (Object line = jdk.readLine(); line != SConstant.EOF; line = jdk.readLine()) {
                    assertReadString(what + ", a line", line.toString(), port.readLine());
                }
                assertEquals(what, SConstant.EOF, port.readLine());
                port = SPort.utf8Input(new ShortReadStream(bytes, most));
                int[] cps = text.codePoints().toArray();
                for (int c : cps) {
                    assertEquals(what, c, port.readChar());
                }
                assertEquals(what, -1, port.readChar());
                // Everything mixed, against the JDK's decoder's port.
                readMixed(what, SPort.utf8Input(new ShortReadStream(bytes, most)), null, jdkDecodedPort(bytes), random);
            }
        }
    }

    /**
     * Reads {@code port} with a random mix of {@code peek-char}, {@code read-char},
     * {@code char-ready?}, {@code read-string} and {@code read-line}, each result compared with the
     * same operation on {@code jdk}, to the end. A file port is always ready; the console port, over
     * {@code console}, is unless the stream has nothing more to give without blocking.
     */
    private static void readMixed(String what, SPort port, ShortReadStream console, SPort jdk, Random random) {
        for (int i = 0;; i++) {
            int op = random.nextInt(12);
            String at = what + ", operation " + i + " (" + op + ")";
            if (op == 0) {
                assertEquals(at, jdk.peekChar(), port.peekChar());
            } else if (op == 1) {
                assertEquals(at, jdk.readChar(), port.readChar());
            } else if (op == 2) {
                assertTrue(at, port.ready() || console != null && console.available() == 0);
            } else if (op <= 6) {
                int k = switch (op) {
                    case 3 -> random.nextInt(4);
                    case 4 -> random.nextInt(2000);
                    case 5 -> 8190 + random.nextInt(5);
                    default -> random.nextInt(20) == 0 ? Integer.MAX_VALUE : random.nextInt(100);
                };
                Object expected = jdk.readString(k);
                assertReadString(at + ", read-string " + k, expected == SConstant.EOF ? null : expected.toString(), port.readString(k));
            } else {
                Object expected = jdk.readLine();
                assertReadString(at + ", read-line", expected == SConstant.EOF ? null : expected.toString(), port.readLine());
                if (expected == SConstant.EOF) {
                    break;
                }
            }
        }
        assertEquals(what, -1, port.peekChar());
        assertEquals(what, -1, port.readChar());
    }

    /**
     * A sequence of 2, 3 or 4 bytes cut by the end of the port's 8 KB buffer, after each of its
     * bytes but the last, is read whole, by {@code read-char}, {@code peek-char},
     * {@code read-string} and {@code read-line}; cut and never completed, it is replaced as the
     * JDK's decoder replaced it. Through a file and through the port's own stream, whose first read
     * fills the buffer.
     */
    @Test
    public void utf8SequenceSplitAcrossTwoBuffers() throws IOException {
        String[] characters = {"é", "中", "😀"};
        int caseNumber = 0;
        StringBuilder program = new StringBuilder("""
                        (define (copy-chars in out)
                          (let loop ()
                            (peek-char in)
                            (let ((c (read-char in)))
                              (unless (eof-object? c) (write-char c out) (loop)))))
                        (define (copy-strings k in out)
                          (let loop ()
                            (let ((s (read-string k in)))
                              (unless (eof-object? s) (write-string s out) (loop)))))
                        (define (copy-lines in out)
                          (let loop ()
                            (let ((l (read-line in)))
                              (unless (eof-object? l) (write-string l out) (newline out) (loop)))))
                        (define (copy name how)
                          (call-with-port (open-input-file name)
                            (lambda (in) (call-with-port (open-output-file (string-append name how))
                                           (lambda (out)
                                             (cond ((equal? how ".chars") (copy-chars in out))
                                                   ((equal? how ".whole") (copy-strings 100000 in out))
                                                   ((equal? how ".8191") (copy-strings 8191 in out))
                                                   (else (copy-lines in out))))))))
                        """);
        List<String[]> expectations = new ArrayList<>();
        for (String character : characters) {
            byte[] sequence = character.getBytes(StandardCharsets.UTF_8);
            for (int cut = 1; cut < sequence.length; cut++) {
                for (boolean complete : new boolean[]{true, false}) {
                    // The sequence's first `cut` bytes end the buffer.
                    ByteArrayOutputStream file = new ByteArrayOutputStream();
                    file.writeBytes("a\n".repeat((8192 - cut) / 2).getBytes(StandardCharsets.UTF_8));
                    if ((8192 - cut) % 2 == 1) {
                        file.write('b');
                    }
                    file.write(sequence, 0, complete ? sequence.length : cut);
                    file.writeBytes("z\r\nend".getBytes(StandardCharsets.UTF_8));
                    byte[] bytes = file.toByteArray();
                    assertEquals(8192 - cut, indexOf(bytes, sequence[0]));
                    String text = jdkDecoded(bytes);
                    assertEquals(complete, text.contains(character));
                    assertEquals(complete, !text.contains("�"));
                    String what = character + " cut after " + cut + (complete ? "" : ", never completed");
                    // The port's own stream.
                    SPort port = SPort.utf8Input(new ByteArrayInputStream(bytes));
                    assertReadString(what, text, port.readString(Integer.MAX_VALUE));
                    port = SPort.utf8Input(new ByteArrayInputStream(bytes));
                    for (int c : text.codePoints().toArray()) {
                        assertEquals(what, c, port.peekChar());
                        assertEquals(what, c, port.readChar());
                    }
                    assertEquals(what, -1, port.readChar());
                    port = SPort.utf8Input(new ByteArrayInputStream(bytes));
                    assertReadString(what, text.substring(0, 8000), port.readString(8000));
                    SPort jdk = jdkDecodedPort(bytes);
                    jdk.readString(8000);
                    for (Object line = jdk.readLine(); line != SConstant.EOF; line = jdk.readLine()) {
                        assertReadString(what + ", a line", line.toString(), port.readLine());
                    }
                    assertEquals(what, SConstant.EOF, port.readLine());
                    // A file.
                    String name = "cut-" + caseNumber++ + ".txt";
                    Files.write(dir.resolve(name), bytes);
                    for (String how : new String[]{".chars", ".whole", ".8191", ".lines"}) {
                        program.append("(copy ").append(file(name)).append(" \"").append(how).append("\")\n");
                        String expected = how.equals(".lines") ? text.replace("\r\n", "\n") + "\n" : text;
                        expectations.add(new String[]{name + how, expected});
                    }
                }
            }
        }
        program.append("(write 'done)");
        assertEquals("done", runWithFiles(program.toString()));
        for (String[] e : expectations) {
            assertEquals(e[0], e[1], Files.readString(dir.resolve(e[0])));
        }
    }

    private static int indexOf(byte[] bytes, byte b) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == b) {
                return i;
            }
        }
        return -1;
    }

    /**
     * A textual input file port reads its stream in bulk, 8 KB at a time, never a byte at a time,
     * whichever way it is read.
     */
    @Test
    public void utf8InputReadsTheStreamInBulk() {
        byte[] bytes = lines(new Random(3), 300, true).getBytes(StandardCharsets.UTF_8);
        String text = new String(bytes, StandardCharsets.UTF_8);
        ShortReadStream in = new ShortReadStream(bytes, Integer.MAX_VALUE);
        SPort port = SPort.utf8Input(in);
        Random random = new Random(4);
        StringBuilder read = new StringBuilder();
        while (true) {
            Object s = switch (random.nextInt(3)) {
                case 0 -> port.readLine();
                case 1 -> port.readString(random.nextInt(3000));
                default -> {
                    int c = port.readChar();
                    yield c < 0 ? SConstant.EOF : new String(Character.toChars(c));
                }
            };
            if (s == SConstant.EOF) {
                break;
            }
            read.append(s);
        }
        assertEquals(text.replaceAll("\r\n|\r|\n", "").length(), read.toString().replaceAll("\r\n|\r|\n", "").length());
        assertEquals(0, in.singleReads);
        assertTrue("bulk reads: " + in.bulkReads + " of " + bytes.length + " bytes", in.bulkReads <= bytes.length / 8000 + 3);
    }

    // ------------------------------------------------------------------------------------------
    // The console input port decodes standard input's UTF-8 itself, as a file port does (M12-10),
    // reading only what each character needs, so interactive input is answered as it comes.

    /**
     * The console input port reads exactly what it read through the JDK's decoder (malformed input
     * replaced the same way, the same strings and eof objects), with the same random mix of reads as
     * the file port ({@link #readMixed}), from a stream that returns at most 1, 2, 3, 5, 8191, 8192,
     * 8193 or any number of bytes a read; {@code char-ready?} is true whenever the stream has more
     * to give.
     */
    @Test
    public void consoleInputReadsWhatTheJdkDecoderRead() throws IOException {
        Random random = new Random(37);
        int[] mosts = {1, 2, 3, 5, 8191, 8192, 8193, Integer.MAX_VALUE};
        for (byte[] bytes : utf8Samples(random)) {
            int[] cps = jdkDecoded(bytes).codePoints().toArray();
            for (int most : mosts) {
                String what = bytes.length + " bytes, at most " + most + " a read";
                ShortReadStream in = new ShortReadStream(bytes, most);
                readMixed(what, SPort.consoleInput(in), in, jdkDecodedPort(bytes), random);
                SPort port = SPort.consoleInput(new ShortReadStream(bytes, most));
                for (int c : cps) {
                    assertEquals(what, c, port.readChar());
                }
                assertEquals(what, -1, port.readChar());
            }
        }
    }

    /**
     * Standard input as a pipe delivers it: each write arrives on its own, a read returns at most
     * what is left of the first write not read yet, and a read with nothing written would block,
     * which fails the test. {@link #finish} ends the input.
     */
    private static final class SlowPipe extends InputStream {
        private final ArrayDeque<byte[]> writes = new ArrayDeque<>();
        /** How much of the first write has been read. */
        private int pos;
        private boolean finished;

        void write(byte[] bytes, int off, int len) {
            if (len > 0) {
                writes.add(Arrays.copyOfRange(bytes, off, off + len));
            }
        }

        void write(String text) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            write(bytes, 0, bytes.length);
        }

        void writeBytes(int... values) {
            byte[] bytes = new byte[values.length];
            for (int i = 0; i < values.length; i++) {
                bytes[i] = (byte) values[i];
            }
            write(bytes, 0, bytes.length);
        }

        void finish() {
            finished = true;
        }

        @Override
        public int read() {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (len == 0) {
                return 0;
            }
            byte[] first = writes.peek();
            if (first == null) {
                if (finished) {
                    return -1;
                }
                throw new AssertionError("the port read the pipe with nothing written: it would block");
            }
            int n = Math.min(len, first.length - pos);
            System.arraycopy(first, pos, b, off, n);
            pos += n;
            if (pos == first.length) {
                writes.poll();
                pos = 0;
            }
            return n;
        }

        @Override
        public int available() {
            int n = -pos;
            for (byte[] w : writes) {
                n += w.length;
            }
            return Math.max(n, 0);
        }
    }

    /**
     * On the console, {@code char-ready?} is true exactly when the bytes written so far make a
     * character not read yet, as the JDK's decoder decodes them before the input ends (a sequence
     * still cut makes none; a malformed one, a U+FFFD), and {@code read-char} and
     * {@code peek-char} then never block: the pipe fails the test if the port reads it with nothing
     * written. Each sample arrives in random pieces of 1 byte to just over the port's 8 KB buffer,
     * so sequences are cut across writes and across the buffer's end.
     */
    @Test
    public void consoleCharReadyIsTrueExactlyWhenACharacterHasArrived() throws IOException {
        Random random = new Random(41);
        for (byte[] bytes : utf8Samples(random)) {
            int[] expected = jdkDecoded(bytes).codePoints().toArray();
            String what = bytes.length + " bytes";
            SlowPipe pipe = new SlowPipe();
            SPort port = SPort.consoleInput(pipe);
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
            ByteBuffer pending = ByteBuffer.allocate(bytes.length);
            // The characters the bytes written so far make, and how many of them have been read.
            int arrived = 0;
            int read = 0;
            int written = 0;
            while (written < bytes.length) {
                int size = switch (random.nextInt(6)) {
                    case 0 -> 1;
                    case 1 -> 2;
                    case 2 -> 3;
                    case 3 -> 1 + random.nextInt(20);
                    case 4 -> 8191 + random.nextInt(3);
                    default -> 1 + random.nextInt(500);
                };
                size = Math.min(size, bytes.length - written);
                pipe.write(bytes, written, size);
                pending.put(bytes, written, size);
                written += size;
                pending.flip();
                CharBuffer chars = CharBuffer.allocate(2 * pending.remaining() + 2);
                decoder.decode(pending, chars, false);
                pending.compact();
                chars.flip();
                arrived += Character.codePointCount(chars, 0, chars.length());
                while (true) {
                    String at = what + ", " + written + " written, " + read + " of " + arrived + " read";
                    boolean ready = port.ready();
                    assertEquals(at, read < arrived, ready);
                    if (!ready) {
                        break;
                    }
                    if (random.nextBoolean()) {
                        assertEquals(at, expected[read], port.peekChar());
                    }
                    assertEquals(at, expected[read], port.readChar());
                    read++;
                }
            }
            pipe.finish();
            while (read < expected.length) {
                assertEquals(what + ", at the end", expected[read++], port.readChar());
            }
            assertEquals(what, -1, port.readChar());
        }
    }

    /**
     * The console input port reads only what it needs, so it answers a line typed at a terminal
     * before the next one comes: {@code read-line} returns a line that a CR ends without waiting
     * for the character after it, and skips the LF of a CR LF that arrives later, on the next read
     * of any kind (a CR LF split across two reads); {@code read-string} returns once it has its
     * characters; a multi-byte sequence split across two buffers (and two writes) is read whole,
     * once it has arrived; malformed input is replaced as the JDK's decoder replaces it.
     */
    @Test
    public void consoleInputReadsOnlyWhatItNeeds() {
        SlowPipe pipe = new SlowPipe();
        SPort port = SPort.consoleInput(pipe);
        pipe.write("a");
        assertEquals('a', port.readChar());
        assertFalse(port.ready());
        // A CR ends the line at once; the LF after it comes later, and whatever reads next skips it.
        pipe.write("one\r");
        assertReadString("CR", "one", port.readLine());
        pipe.write("\n");
        assertFalse("only the LF after a CR", port.ready());
        pipe.write("two\r\nthree\n");
        assertReadString("CR LF", "two", port.readLine());
        assertReadString("LF", "three", port.readLine());
        pipe.write("\r");
        assertReadString("a CR alone", "", port.readLine());
        pipe.write("\r");
        assertReadString("a second CR", "", port.readLine());
        pipe.write("\n");
        pipe.write("x");
        assertEquals("read-char after a CR LF split across writes", 'x', port.readChar());
        pipe.write("b\r");
        assertReadString("CR", "b", port.readLine());
        pipe.write("\n");
        pipe.write("cd");
        assertEquals("peek-char after a CR LF split across writes", 'c', port.peekChar());
        assertReadString("read-string after the peek", "cd", port.readString(2));
        pipe.write("e\r");
        assertReadString("CR", "e", port.readLine());
        pipe.write("\nfg");
        assertReadString("read-string after a CR LF split across writes", "fg", port.readString(2));
        pipe.write("h\r");
        assertReadString("CR", "h", port.readLine());
        pipe.write("\ni\n");
        assertReadString("read-line after a CR LF split across writes", "i", port.readLine());
        // read-char of a CR doesn't skip an LF.
        pipe.write("\r\n");
        assertEquals('\r', port.readChar());
        assertEquals('\n', port.readChar());
        assertFalse(port.ready());
        // A multi-byte sequence that the buffer's end cuts, which the next write completes.
        pipe.write("a".repeat(8191));
        pipe.writeBytes(0xE4);
        assertReadString("up to the cut", "a".repeat(8191), port.readString(8191));
        assertFalse("a third of a character", port.ready());
        pipe.writeBytes(0xB8);
        assertFalse("two thirds of a character", port.ready());
        pipe.writeBytes(0xAD);
        assertTrue(port.ready());
        assertEquals(0x4E2D, port.readChar());
        pipe.write("b".repeat(8190));
        pipe.writeBytes(0xF0, 0x9F);
        assertTrue(port.ready());
        pipe.writeBytes(0x98, 0x80, '\n');
        assertReadString("a line with a sequence cut by the buffer's end", "b".repeat(8190) + "😀", port.readLine());
        pipe.write("é".repeat(5000) + "\n");
        assertReadString("a line longer than the buffer", "é".repeat(5000), port.readLine());
        // Malformed input, as the JDK's decoder replaced it: an encoded surrogate is one U+FFFD; a
        // sequence that a byte can't continue is one, and so is one the input's end cuts.
        pipe.writeBytes(0xED, 0xA0, 0x80, 'z', 0xE4, 'y', '\n');
        assertReadString("malformed", "�z�y", port.readLine());
        pipe.writeBytes(0xE4, 0xB8);
        assertFalse(port.ready());
        pipe.finish();
        assertEquals("cut by the end", 0xFFFD, port.readChar());
        assertEquals(-1, port.readChar());
    }

    /**
     * Through the launcher, standard input is a pipe whose bytes arrive slowly, a few at a time: the
     * program answers each read as soon as its bytes have come, without waiting for a buffer's
     * worth, for a character cut across writes, a line that a CR ends, the LF after it written
     * later, and a {@code read-string} whose first character arrives in pieces.
     */
    @Test
    public void processAnswersAPipeAsItsBytesArrive() throws IOException, InterruptedException {
        Path program = dir.resolve("answers.scm");
        Files.writeString(program, """
                        (import (scheme base) (scheme write))
                        (define (answer x) (write x) (newline) (flush-output-port))
                        (answer (read-char))
                        (answer (read-char))
                        (answer (read-line))
                        (answer (read-char))
                        (answer (read-string 2))
                        (answer (read-line))
                        (answer (eof-object? (read-char)))
                        """);
        String java = ProcessHandle.current().info().command().orElseThrow();
        String modulePath = System.getProperty("jdk.module.path");
        assertTrue("the tests run on the module path", modulePath != null);
        Process process = new ProcessBuilder(java, "--module-path", modulePath, "--enable-native-access=org.graalvm.truffle", "--sun-misc-unsafe-memory-access=allow", "-m",
                        "scheme.launcher/scheme.launcher.SchemeMain", program.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            BufferedReader answers = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            OutputStream in = process.getOutputStream();
            // Each step's pieces, written 100 ms apart, then the answer they make (a null piece
            // closes the pipe).
            Object[][] steps = {
                            {new byte[]{'a'}, "#\\a"},
                            {new byte[]{(byte) 0xE4}, new byte[]{(byte) 0xB8}, new byte[]{(byte) 0xAD}, "#\\中"},
                            {"line\r".getBytes(StandardCharsets.UTF_8), "\"line\""},
                            {new byte[]{'\n'}, new byte[]{'b'}, "#\\b"},
                            {new byte[]{(byte) 0xC3}, new byte[]{(byte) 0xA9, 'x'}, "\"éx\""},
                            {"last\n".getBytes(StandardCharsets.UTF_8), "\"last\""},
                            {null, "#t"},
            };
            for (Object[] step : steps) {
                for (int i = 0; i < step.length - 1; i++) {
                    if (step[i] == null) {
                        in.close();
                    } else {
                        Thread.sleep(100);
                        in.write((byte[]) step[i]);
                        in.flush();
                    }
                }
                CompletableFuture<String> answer = CompletableFuture.supplyAsync(() -> {
                    try {
                        return answers.readLine();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
                try {
                    assertEquals(step[step.length - 1], answer.get(60, TimeUnit.SECONDS));
                } catch (ExecutionException | TimeoutException e) {
                    throw new AssertionError("no answer once the bytes for " + step[step.length - 1] + " were written", e);
                }
            }
            assertTrue(process.waitFor(60, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
        } finally {
            process.destroyForcibly();
        }
    }

    /**
     * The port operations compile without performance warnings (design.md D10, "Ports in compiled
     * code"): each kind of port goes through the same procedures, so their compiled code sees a
     * port that isn't a constant, and partial evaluation must reach the port's stream without a
     * virtual call it can't inline. The program's output is checked too, buffering included: what
     * was written to each file, standard output and the in-memory ports.
     */
    @Test
    public void portOperationsCompileWithoutPerformanceWarnings() throws IOException {
        // Each iteration reads a line of 5 characters from each textual port (3 a character or two
        // at a time, then the rest of the line, ending in LF, CR LF or CR) and 5 bytes from each
        // binary one.
        int n = 2000;
        Files.writeString(dir.resolve("in.txt"), "bbbde\r\n".repeat(n + 100));
        Files.write(dir.resolve("in.bin"), new byte[5 * n + 1000]);
        String program = """
                        (define text-out (open-output-file %s))
                        (define binary-out (open-binary-output-file %s))
                        (define text-in (open-input-file %s))
                        (define binary-in (open-binary-input-file %s))
                        (define string-out (open-output-string))
                        (define bytes-out (open-output-bytevector))
                        (define string-in
                          (open-input-string
                           (let ((p (open-output-string)))
                             (do ((i 0 (+ i 1))) ((= i (+ %d 100)) (get-output-string p)) (write-string "aaade\n" p)))))
                        (define bytes-in (open-input-bytevector (make-bytevector (+ (* 5 %d) 1000) 7)))
                        (define (text-output p i)
                          (display i p) (write 'x p) (write-char #\\y p) (write-string "z" p) (newline p)
                          (flush-output-port p))
                        (define (binary-output p i)
                          (write-u8 (modulo i 256) p) (write-bytevector (bytevector 1 2) p) (flush-output-port p))
                        (define into (make-bytevector 4))
                        ;; Each returns the number of characters or bytes it read.
                        (define (text-input p)
                          (char-ready? p) (peek-char p)
                          (+ (if (char? (read-char p)) 1 0) (string-length (read-string 2 p)) (string-length (read-line p))))
                        (define (binary-input p)
                          (u8-ready? p) (peek-u8 p)
                          (+ (if (integer? (read-u8 p)) 1 0) (bytevector-length (read-bytevector 2 p))
                             (read-bytevector! into p 1 3)))
                        (define chars 0)
                        (define bytes 0)
                        (let loop ((i 0))
                          (when (< i %d)
                            (text-output string-out i) (text-output text-out i) (text-output (current-output-port) i)
                            (binary-output bytes-out i) (binary-output binary-out i)
                            (for-each (lambda (p) (set! chars (+ chars (text-input p))))
                                      (list string-in text-in (current-input-port)))
                            (for-each (lambda (p) (set! bytes (+ bytes (binary-input p))))
                                      (list bytes-in binary-in))
                            (loop (+ i 1))))
                        (close-port text-out)
                        (close-port binary-out)
                        (unless (and (= chars (* 15 %d)) (= bytes (* 10 %d))
                                     (= (string-length (get-output-string string-out)) %d)
                                     (= (bytevector-length (get-output-bytevector bytes-out)) (* 3 %d)))
                          (error "wrong input or in-memory output" chars bytes))
                        """.formatted(file("out.txt"), file("out.bin"), file("in.txt"), file("in.bin"), n, n, n, n, n,
                        textOutput(n).length(), n);
        Path log = Files.createTempFile(dir, "engine", ".log");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Context.Builder builder = Context.newBuilder(SCHEME).allowIO(IOAccess.ALL).allowExperimentalOptions(true).out(out).in(
                        new ByteArrayInputStream("cccde\r".repeat(n).getBytes(StandardCharsets.UTF_8))).option("log.file", log.toString());
        builder.option("engine.Compilation", "true").option("engine.CompileImmediately", "false").option("engine.BackgroundCompilation", "false");
        builder.option("engine.SingleTierCompilationThreshold", "100").option("engine.FirstTierCompilationThreshold", "100");
        builder.option("engine.LastTierCompilationThreshold", "500");
        builder.option("compiler.TracePerformanceWarnings", "all").option("engine.TraceCompilation", "true");
        Context context;
        try {
            context = builder.build();
        } catch (IllegalArgumentException e) {
            assumeTrue("no optimizing Truffle runtime: " + e.getMessage(), false);
            throw e;
        }
        try (Context c = context) {
            c.eval(SCHEME, program);
        }
        String engineLog = Files.readString(log);
        assertTrue(engineLog, engineLog.contains("opt done") && engineLog.contains("text-output") && engineLog.contains("binary-input"));
        assertFalse(engineLog, engineLog.contains("perf warn"));
        assertEquals(textOutput(n), out.toString(StandardCharsets.UTF_8));
        assertEquals(textOutput(n), Files.readString(dir.resolve("out.txt")));
        byte[] expected = new byte[3 * n];
        for (int i = 0; i < n; i++) {
            expected[3 * i] = (byte) i;
            expected[3 * i + 1] = 1;
            expected[3 * i + 2] = 2;
        }
        assertArrayEquals(expected, Files.readAllBytes(dir.resolve("out.bin")));
    }

    /** What {@code text-output} writes for 0 to {@code n - 1}. */
    private static String textOutput(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(i).append("xyz\n");
        }
        return sb.toString();
    }
}
