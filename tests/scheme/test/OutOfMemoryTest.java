package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * design.md D8, "Running out of memory" (plan.md M14-08): a size past an implementation limit that
 * can be checked before allocating is a Scheme error, which handlers see; a heap that fills is the
 * VM's {@code OutOfMemoryError}, which Truffle gives the embedder as a resource exhausted, not an
 * internal error, and after which the context goes on. The heap's cases run in a child JVM with a
 * small heap ({@link #main}); tests/memory has the same through the launchers
 * ({@code //tests:memory}).
 */
public class OutOfMemoryTest {

    private static final String SCHEME = "scheme";
    private static final String HEAP = "-Xmx64m";
    private static final String GROW = "(define (grow l) (grow (cons (make-vector 1000 0) l)))";
    private static final String DEEP = "(define (deep n) (+ 1 (deep (+ n 1))))";
    private static final String DEPTH = "(define (depth n) (if (= n 0) 0 (+ 1 (depth (- n 1)))))";

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    /** What a handler sees of evaluating {@code expression}: its value, or the condition's kind. */
    private static String caught(Context context, String expression) {
        return context.eval(SCHEME, "(guard (e ((read-error? e) 'read-error) ((error-object? e) (list 'error (error-object-message e)))) " + expression + ")").toString();
    }

    private static void assertError(Context context, String who, String expression) {
        String result = caught(context, expression);
        assertTrue(expression + ": " + result, result.startsWith("(error \"" + who + ":"));
    }

    /** Lengths past Java's arrays': an error before anything is allocated. */
    @Test
    public void lengthsPastAnArraysAreErrors() {
        try (Context context = Context.create(SCHEME)) {
            assertError(context, "make-vector", "(make-vector (expt 10 10))");
            assertError(context, "make-vector", "(make-vector (expt 2 31) 0)");
            assertError(context, "make-string", "(make-string (expt 2 31) #\\a)");
            assertError(context, "make-bytevector", "(make-bytevector 3000000000 0)");
        }
    }

    /**
     * Exact numbers past BigInteger's range (2^31 bits): an error, where they were internal errors
     * (BigInteger's {@code ArithmeticException} and BigDecimal's {@code NumberFormatException}) or
     * minutes of multiplying first. An inexact one is an infinity, as before.
     */
    @Test
    public void exactNumbersPastTheRangeAreErrors() {
        try (Context context = Context.create(SCHEME)) {
            // 3 to the 1.5e9 has 2.38e9 bits: past the range, found before multiplying.
            assertError(context, "expt", "(expt 3 1500000000)");
            assertError(context, "expt", "(expt -3 -1500000000)");
            // 2^(2^31 - 1) has 2^31 bits, one past the range; 2^(2^31 - 2) would fit.
            assertError(context, "expt", "(expt 2 2147483647)");
            assertError(context, "expt", "(expt -7 (- (expt 2 30)))");
            assertError(context, "expt", "(expt 2/3 2000000000)");
            assertEquals("1", caught(context, "(expt 1 (expt 10 9))"));
            assertEquals("-1", caught(context, "(expt -1 2147483647)"));
            assertError(context, "string->number", "(string->number \"#e1e-1000000000\")");
            assertError(context, "string->number", "(string->number \"#e1e1000000000\")");
            assertError(context, "string->number", "(string->number \"#e1.5e-3000000000\")");
            assertError(context, "string->number", "(string->number \"#e1e-1000000000+i\")");
            assertEquals("+inf.0", caught(context, "(string->number \"1e3000000000\")"));
            assertEquals("0.0", caught(context, "(string->number \"1e-3000000000\")"));
            assertEquals("read-error", caught(context, "(read (open-input-string \"#e1e-1000000000\"))"));
            // In a program's text, a syntax error, located (design.md D13).
            PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval(SCHEME, "(+ 1 #e1e-1000000000)"));
            assertTrue(e.getMessage(), e.isSyntaxError());
            assertFalse(e.getMessage(), e.isInternalError());
            assertTrue(e.getMessage(), e.getMessage().contains("exact number out of range"));
            assertEquals("3", context.eval(SCHEME, "(+ 1 2)").toString());
        }
    }

    /** The heap's cases, in a child JVM with a 64 MB heap; its {@link #main} asserts them. */
    @Test
    public void anEmbedderSeesTheHeapRunOutAndGoesOn() throws IOException, InterruptedException {
        String java = ProcessHandle.current().info().command().orElseThrow();
        String modulePath = System.getProperty("jdk.module.path");
        assertNotNull("the tests run on the module path", modulePath);
        Path out = folder.newFile().toPath();
        ProcessBuilder builder = new ProcessBuilder(List.of(java, HEAP, "-XX:+UseSerialGC", "--module-path", modulePath, "--add-modules", "ALL-MODULE-PATH",
                        "--enable-native-access=org.graalvm.truffle", "--sun-misc-unsafe-memory-access=allow", "-m", "scheme.test/" + OutOfMemoryTest.class.getName()));
        builder.redirectErrorStream(true).redirectOutput(out.toFile());
        Process process = builder.start();
        if (!process.waitFor(300, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("timed out: " + Files.readString(out, StandardCharsets.UTF_8));
        }
        String output = Files.readString(out, StandardCharsets.UTF_8);
        assertEquals(output, 0, process.exitValue());
        assertTrue(output, output.endsWith("done\n"));
    }

    /**
     * Evaluating {@code text} runs out of heap: Truffle's resource exhausted, neither an internal
     * error nor a cancel, so the context goes on.
     */
    private static PolyglotException exhausted(Context context, String text) {
        PolyglotException e = assertThrows(text, PolyglotException.class, () -> context.eval(SCHEME, text));
        String what = text + ": " + e;
        assertTrue(what, e.isResourceExhausted());
        assertFalse(what, e.isInternalError());
        assertFalse(what, e.isCancelled());
        assertTrue(what, e.isGuestException());
        assertFalse(what, e.isExit());
        return e;
    }

    /** The child JVM of {@link #anEmbedderSeesTheHeapRunOutAndGoesOn}, with a 64 MB heap. */
    public static void main(String[] args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            context.eval(SCHEME, GROW + DEEP + DEPTH + "(define p (make-parameter 1)) (define seen '())");
            exhausted(context, "(grow '())");
            assertEquals("3", context.eval(SCHEME, "(+ 1 2)").toString());
            // A length Java's arrays can have, past the heap.
            exhausted(context, "(make-vector 100000000 0)");
            // A recursion's frames spill to the heap until it is full, never the Java stack.
            exhausted(context, "(deep 0)");
            // No handler sees it, and no after-thunk runs; the next call has the dynamic
            // environment of before (its parameters, its handlers).
            exhausted(context, "(with-exception-handler (lambda (e) (set! seen (cons 'handler seen)) 0)" +
                            "  (lambda () (guard (e (#t (set! seen (cons 'guard seen))))" +
                            "    (dynamic-wind (lambda () #f) (lambda () (parameterize ((p 2)) (grow '()))) (lambda () (set! seen (cons 'after seen)))))))");
            assertEquals("()", context.eval(SCHEME, "seen").toString());
            assertEquals("1", context.eval(SCHEME, "(p)").toString());
            String handled = context.eval(SCHEME, "(guard (e (#t (list 'error (error-object-message e)))) (car 1))").toString();
            assertTrue(handled, handled.startsWith("(error \"car:"));
            // The heap is free again: the recursion that filled it runs, at a depth it holds.
            assertEquals("100000", context.eval(SCHEME, "(depth 100000)").toString());
            assertEquals("(1 2 3)", context.eval(SCHEME, "(let ((v (make-vector 3 0))) (vector-set! v 0 1) (vector-set! v 1 2) (vector-set! v 2 3) (vector->list v))").toString());
            context.eval(SCHEME, "(display \"output\")");
        }
        assertEquals("output", out.toString(StandardCharsets.UTF_8));
        System.out.println("done");
    }
}
