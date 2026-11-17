package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Invoking a full continuation ({@code %apply-continuation}) returns a request that travels up to
 * the root prompt (an APPLY {@code Capture}), as every other control transfer of a full
 * continuation does, instead of throwing a Java exception to it (design.md "Continuations"). A
 * thrown one passed the exception handlers of the host-compiled interpreter's inlined calls, which
 * the host compiler can leave out of the compiled code: then each throw deoptimized it, and in
 * {@code puzzle}, whose k is invoked from inside a loop, it was compiled again without them, about
 * every 250 ms, for the whole run (2.2 times as long in the interpreter).
 */
public class ApplyContinuationTest {

    private static final String SCHEME = "scheme";

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static String output(String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            context.eval(SCHEME, program);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String eval(String program) {
        try (Context context = Context.create(SCHEME)) {
            return context.eval(SCHEME, program).toString();
        }
    }

    /**
     * {@code puzzle}'s shape: k is referenced from a loop's body, a lambda, so call/cc captures a
     * full continuation, and k is invoked (a non-tail call) from a frame that the root prompt
     * resumed after the nested call/cc's capture. With the interpreter only, and every exception the
     * JVM throws logged: none is the runtime's.
     */
    @Test
    public void invokingAFullContinuationThrowsNoJavaException() throws IOException, InterruptedException {
        Path program = folder.getRoot().toPath().resolve("trial.scm");
        Files.writeString(program, """
                        (import (scheme base) (scheme write))
                        (define (trial j)
                          (call-with-current-continuation
                           (lambda (return)
                             (do ((i 0 (+ i 1))) ((= i 2) #f)
                               (if (or (= j 0) (trial (- j 1))) (return #t))))))
                        (define (run n found)
                          (if (= n 0) found (run (- n 1) (if (trial 3) (+ found 1) found))))
                        (write (run 2000 0))
                        """, StandardCharsets.UTF_8);
        String java = ProcessHandle.current().info().command().orElseThrow();
        String modulePath = System.getProperty("jdk.module.path");
        assertNotNull("the tests run on the module path", modulePath);
        Path exceptions = folder.newFile().toPath();
        Path out = folder.newFile().toPath();
        Path err = folder.newFile().toPath();
        Process process = new ProcessBuilder(List.of(java, "-Xlog:exceptions=info:file=" + exceptions, "-Dpolyglot.engine.AllowExperimentalOptions=true",
                        "-Dpolyglot.engine.Compilation=false", "--module-path", modulePath, "--enable-native-access=org.graalvm.truffle", "--sun-misc-unsafe-memory-access=allow",
                        "-m", "scheme.launcher/scheme.launcher.SchemeMain", program.toString())).directory(folder.getRoot()).redirectOutput(out.toFile()).redirectError(
                                        err.toFile()).start();
        process.getOutputStream().close();
        assertTrue("the program ends", process.waitFor(120, TimeUnit.SECONDS));
        assertEquals(Files.readString(err, StandardCharsets.UTF_8), 0, process.exitValue());
        assertEquals("2000", Files.readString(out, StandardCharsets.UTF_8));
        String thrown = Files.readAllLines(exceptions, StandardCharsets.UTF_8).stream().filter(line -> line.contains("<a 'scheme/")).collect(Collectors.joining("\n"));
        assertEquals("", thrown);
    }

    @Test
    public void leavesThePromptsAndExtentsItCrosses() {
        // top is a full continuation (a nested lambda refers to it); it leaves dynamic-wind's
        // extent, an escape-only call/cc's prompt and guard's prompt, from a non-tail call.
        assertEquals("(41 (in out))", eval("""
                        (let* ((trace '())
                               (v (call/cc
                                   (lambda (top)
                                     (guard (e (#t 'caught))
                                       (call/cc
                                        (lambda (esc)
                                          (dynamic-wind
                                           (lambda () (set! trace (cons 'in trace)))
                                           (lambda () (+ 1 (top 41)))
                                           (lambda () (set! trace (cons 'out trace)))))))))))
                          (list v (reverse trace)))
                        """));
        // With several values, and from inside parameterize, whose binding it leaves.
        assertEquals("((1 2 #t) #f)", eval("""
                        (define p (make-parameter #f))
                        (let ((vals (call-with-values
                                     (lambda ()
                                       (call/cc (lambda (k) (parameterize ((p #t)) (list ((lambda () (k 1 2 (p)))))))))
                                     list)))
                          (list vals (p)))
                        """));
    }

    @Test
    public void reentersAContinuationCapturedUnderAPrompt() {
        // The continuation's segments include guard's prompt, flattened into a marker: each re-entry
        // returns through it, and an error raised after a re-entry is still guard's to handle.
        assertEquals("(100 101 102 caught)", output("""
                        (define k2 #f)
                        (define results '())
                        (define r (guard (e (#t 'caught)) (+ 100 (call/cc (lambda (k) (set! k2 k) 0)))))
                        (set! results (cons r results))
                        (cond ((< (length results) 3) (k2 (length results)))
                              ((= (length results) 3) (k2 'not-a-number)))
                        (write (reverse results))
                        """));
    }

    @Test
    public void abandonsADeepRecursion() {
        // Spilled to the heap on the way down (StackBudget): those frames are abandoned too.
        assertEquals("bottom", eval("""
                        (define (deep n k) (if (= n 0) (k 'bottom) (+ 1 (deep (- n 1) k))))
                        (call/cc (lambda (k) ((lambda () (deep 100000 k)))))
                        """));
    }

    @Test
    public void throughInterop() {
        // A continuation captured in one interop call, invoked from another: the later call returns
        // what the rest of the first returns (InteropCallRootNode).
        try (Context context = Context.create(SCHEME)) {
            context.eval(SCHEME, "(define saved #f) (define (f) (+ 1 (call/cc (lambda (k) (set! saved k) 1))))");
            Value f = context.eval(SCHEME, "f");
            assertEquals(2, f.execute().asInt());
            Value g = context.eval(SCHEME, "(lambda (x) (* 1000 (saved x)))");
            assertEquals(11, g.execute(10).asInt());
        }
    }
}
