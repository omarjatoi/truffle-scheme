package scheme.test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessMode;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
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
import scheme.runtime.value.SPort;

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
            return new SeekableByteChannel() {
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

    /**
     * The port operations compile without performance warnings (design.md D10, "Ports in compiled
     * code"): each kind of port goes through the same procedures, so their compiled code sees a
     * port that isn't a constant, and partial evaluation must reach the port's stream without a
     * virtual call it can't inline. The program's output is checked too, buffering included: what
     * was written to each file, standard output and the in-memory ports.
     */
    @Test
    public void portOperationsCompileWithoutPerformanceWarnings() throws IOException {
        Files.writeString(dir.resolve("in.txt"), "b".repeat(3000));
        Files.write(dir.resolve("in.bin"), new byte[3000]);
        int n = 2000;
        String program = """
                        (define text-out (open-output-file %s))
                        (define binary-out (open-binary-output-file %s))
                        (define text-in (open-input-file %s))
                        (define binary-in (open-binary-input-file %s))
                        (define string-out (open-output-string))
                        (define bytes-out (open-output-bytevector))
                        (define string-in (open-input-string (make-string 3000 #\\a)))
                        (define bytes-in (open-input-bytevector (make-bytevector 3000 7)))
                        (define (text-output p i)
                          (display i p) (write 'x p) (write-char #\\y p) (write-string "z" p) (newline p)
                          (flush-output-port p))
                        (define (binary-output p i)
                          (write-u8 (modulo i 256) p) (write-bytevector (bytevector 1 2) p) (flush-output-port p))
                        (define (text-input p) (char-ready? p) (peek-char p) (read-char p))
                        (define (binary-input p) (u8-ready? p) (peek-u8 p) (read-u8 p))
                        (define chars 0)
                        (define bytes 0)
                        (let loop ((i 0))
                          (when (< i %d)
                            (text-output string-out i) (text-output text-out i) (text-output (current-output-port) i)
                            (binary-output bytes-out i) (binary-output binary-out i)
                            (for-each (lambda (p) (if (char? (text-input p)) (set! chars (+ chars 1))))
                                      (list string-in text-in (current-input-port)))
                            (for-each (lambda (p) (if (integer? (binary-input p)) (set! bytes (+ bytes 1))))
                                      (list bytes-in binary-in))
                            (loop (+ i 1))))
                        (close-port text-out)
                        (close-port binary-out)
                        (unless (and (= chars (* 3 %d)) (= bytes (* 2 %d))
                                     (= (string-length (get-output-string string-out)) %d)
                                     (= (bytevector-length (get-output-bytevector bytes-out)) (* 3 %d)))
                          (error "wrong input or in-memory output" chars bytes))
                        """.formatted(file("out.txt"), file("out.bin"), file("in.txt"), file("in.bin"), n, n, n,
                        textOutput(n).length(), n);
        Path log = Files.createTempFile(dir, "engine", ".log");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Context.Builder builder = Context.newBuilder(SCHEME).allowIO(IOAccess.ALL).allowExperimentalOptions(true).out(out).in(
                        new ByteArrayInputStream("c".repeat(n).getBytes(StandardCharsets.UTF_8))).option("log.file", log.toString());
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
