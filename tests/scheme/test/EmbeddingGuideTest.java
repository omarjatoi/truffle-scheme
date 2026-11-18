package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * M6-07: the Java samples of docs/embedding.md. Each region between {@code // readme-snippet: name}
 * and {@code // readme-snippet-end} is a Java block of that guide, and the readme check
 * ({@code bazel test //tests:readme}, in CI's tests step) fails if the two differ, so the guide's samples are
 * code that compiles and passes. EmbeddingTest covers the same API in depth.
 */
public class EmbeddingGuideTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void evalAndCall() {
        // readme-snippet: eval
        try (Context context = Context.create("scheme")) {
            Value three = context.eval("scheme", "(+ 1 2)");
            assertEquals(3, three.asInt());

            // Each eval runs in the interaction environment, which persists, as at the REPL.
            context.eval("scheme", "(define (square x) (* x x))");
            Value square = context.eval("scheme", "square");
            assertEquals(144, square.execute(12).asInt());
            assertEquals(6.25, square.execute(2.5).asDouble(), 0);
        }
        // readme-snippet-end
    }

    @Test
    public void output() {
        // readme-snippet: output
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("scheme").out(out).build()) {
            context.eval("scheme", "(display \"hello\") (newline)");
        }
        assertEquals("hello\n", out.toString(StandardCharsets.UTF_8));
        // readme-snippet-end
    }

    @Test
    public void program() throws IOException {
        Path libraries = folder.newFolder("lib").toPath();
        Files.createDirectories(libraries.resolve("examples"));
        Files.writeString(libraries.resolve("examples/greet.sld"), """
                        (define-library (examples greet)
                          (export greet)
                          (import (scheme base))
                          (begin
                            (define (greet name)
                              (string-append "Hello from a library, " name "!"))))
                        """);
        Path file = folder.getRoot().toPath().resolve("greet.scm");
        Files.writeString(file, """
                        (import (scheme base) (scheme write) (scheme process-context) (examples greet))

                        (display (greet "Ada"))
                        (newline)
                        (exit 3)
                        """);
        // readme-snippet: program
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source program = Source.newBuilder("scheme", file.toFile()).build();
        Context.Builder builder = Context.newBuilder("scheme").out(out);
        builder.allowIO(IOAccess.ALL); // to read the libraries
        builder.option("scheme.LibraryPath", libraries.toString()); // what -I adds to
        try (Context context = builder.build()) {
            context.eval(program);
            fail("the program calls exit");
        } catch (PolyglotException e) {
            assertTrue(e.isExit());
            assertEquals(3, e.getExitStatus());
        }
        assertEquals("Hello from a library, Ada!\n", out.toString(StandardCharsets.UTF_8));
        // readme-snippet-end
    }

    @Test
    public void engine() throws IOException {
        Path libraries = folder.newFolder("lib").toPath();
        Files.createDirectories(libraries.resolve("examples"));
        Files.writeString(libraries.resolve("examples/greet.sld"), """
                        (define-library (examples greet)
                          (export greet)
                          (import (scheme base))
                          (begin
                            (define (greet name)
                              (string-append "Hello from a library, " name "!"))))
                        """);
        // readme-snippet: engine
        Source program = Source.create("scheme", "(import (scheme base) (examples greet)) (greet \"Ada\")");
        // Every context of this engine takes its options, unless the context's builder gives others.
        try (Engine engine = Engine.newBuilder("scheme").option("scheme.LibraryPath", libraries.toString()).build()) {
            for (int i = 0; i < 2; i++) {
                try (Context context = Context.newBuilder("scheme").engine(engine).allowIO(IOAccess.ALL).build()) {
                    assertEquals("Hello from a library, Ada!", context.eval(program).asString());
                }
            }
        }
        // readme-snippet-end
    }

    @Test
    public void limits() {
        // readme-snippet: limits
        ResourceLimits limits = ResourceLimits.newBuilder().statementLimit(1_000_000, null).build();
        try (Context context = Context.newBuilder("scheme").resourceLimits(limits).build()) {
            assertEquals(55, context.eval("scheme", "(let loop ((i 0) (sum 0)) (if (> i 10) sum (loop (+ i 1) (+ sum i))))").asInt());
            context.eval("scheme", "(let loop () (loop))");
            fail("the loop ended");
        } catch (PolyglotException e) {
            // The context is cancelled: it can't be used again.
            assertTrue(e.isResourceExhausted());
        }
        // readme-snippet-end
    }

    @Test
    public void schemeValuesInJava() {
        try (Context context = Context.create("scheme")) {
            // readme-snippet: scheme-values
            assertEquals(42L, context.eval("scheme", "42").asLong());
            assertEquals(BigInteger.TWO.pow(100), context.eval("scheme", "(expt 2 100)").asBigInteger());
            assertEquals(0.5, context.eval("scheme", "1/2").asDouble(), 0);
            assertEquals("λ", context.eval("scheme", "#\\λ").asString());
            assertTrue(context.eval("scheme", "(< 1 2)").asBoolean());

            Value vector = context.eval("scheme", "(vector 1 2 3)");
            assertEquals(3, vector.getArraySize());
            vector.setArrayElement(0, 10);

            List<Integer> items = new ArrayList<>();
            Value iterator = context.eval("scheme", "(list 1 2 3)").getIterator();
            while (iterator.hasIteratorNextElement()) {
                items.add(iterator.getIteratorNextElement().asInt());
            }
            assertEquals(List.of(1, 2, 3), items);
            Value improper = context.eval("scheme", "'(1 2 . 3)");
            assertEquals(3, improper.getMember("cdr").getMember("cdr").asInt());

            Value point = context.eval("scheme", """
                            (define-record-type point (make-point x y) point? (x point-x) (y point-y))
                            (make-point 3 4)
                            """);
            assertEquals(4, point.getMember("y").asInt());
            assertEquals("point", point.getMetaObject().getMetaSimpleName());

            // toString is the value's write syntax.
            assertEquals("(1 \"two\" #\\3 four)", context.eval("scheme", "(list 1 \"two\" #\\3 'four)").toString());
            // readme-snippet-end
        }
    }

    @Test
    public void javaValuesInScheme() {
        try (Context context = Context.create("scheme")) {
            // readme-snippet: java-values
            Value kind = context.eval("scheme", """
                            (lambda (x)
                              (cond ((exact-integer? x) 'exact-integer)
                                    ((real? x) 'flonum)
                                    ((char? x) 'char)
                                    ((string? x) 'string)
                                    ((boolean? x) 'boolean)
                                    (else 'foreign)))
                            """);
            assertEquals("exact-integer", kind.execute(42).toString());
            assertEquals("exact-integer", kind.execute(BigInteger.TEN.pow(30)).toString());
            assertEquals("flonum", kind.execute(2.5f).toString());
            assertEquals("char", kind.execute('c').toString());
            assertEquals("string", kind.execute("text").toString());
            assertEquals("boolean", kind.execute(true).toString());
            assertEquals("foreign", kind.execute(new StringBuilder()).toString());
            assertEquals("foreign", kind.execute((Object) null).toString());

            // A foreign value comes back as the same Java object.
            StringBuilder host = new StringBuilder();
            Value identity = context.eval("scheme", "(lambda (x) (car (list x)))");
            assertSame(host, identity.execute(host).asHostObject());
            // readme-snippet-end
        }
    }

    @Test
    public void bindings() {
        try (Context context = Context.create("scheme")) {
            // readme-snippet: bindings
            Value bindings = context.getBindings("scheme");
            bindings.putMember("limit", 10);
            assertEquals(11, context.eval("scheme", "(+ limit 1)").asInt());

            context.eval("scheme", "(define (twice f x) (f (f x)))");
            Value twice = bindings.getMember("twice");
            Value cdr = bindings.getMember("cdr");
            assertEquals("(3)", twice.execute(cdr, context.eval("scheme", "'(1 2 3)")).toString());
            assertTrue(bindings.getMember("string-append").canExecute());
            assertFalse(bindings.hasMember("no-such-variable"));
            // readme-snippet-end
        }
    }

    @Test
    public void stackSize() throws InterruptedException {
        // readme-snippet: stack-size
        long stackSize = 64L << 20;
        AtomicLong depth = new AtomicLong();
        Thread thread = new Thread(null, () -> {
            // Created on this thread, whose stack size the context is told: nothing measures it.
            try (Context context = Context.newBuilder("scheme").option("scheme.StackSize", Long.toString(stackSize)).build()) {
                context.eval("scheme", "(define (depth n) (if (= n 0) 0 (+ 1 (depth (- n 1)))))");
                depth.set(context.eval("scheme", "(depth 20000)").asLong());
            }
        }, "scheme", stackSize);
        thread.start();
        thread.join();
        assertEquals(20000, depth.get());
        // readme-snippet-end
    }

    @Test
    public void errors() {
        try (Context context = Context.create("scheme")) {
            // readme-snippet: errors
            try {
                context.eval("scheme", "(error \"bad input:\" 42)");
                fail("error returned");
            } catch (PolyglotException e) {
                assertTrue(e.isGuestException());
                assertEquals("bad input: 42", e.getMessage());
                // The guest object is the error object that error raised.
                Value error = e.getGuestObject();
                assertEquals("bad input:", error.getMember("message").asString());
                assertEquals(42, error.getMember("irritants").getIterator().getIteratorNextElement().asInt());
            }
            Value check = context.eval("scheme", "(lambda (x) (if (negative? x) (raise (list 'negative x)) x))");
            try {
                check.execute(-1);
                fail("raise returned");
            } catch (PolyglotException e) {
                assertTrue(e.isGuestException());
                assertEquals("uncaught exception: (negative -1)", e.getMessage());
                assertEquals("(negative -1)", e.getGuestObject().toString());
            }
            // The context goes on working.
            assertEquals(5, check.execute(5).asInt());
            // readme-snippet-end
        }
    }

    @Test
    public void continuations() {
        try (Context context = Context.create("scheme")) {
            // readme-snippet: continuations
            context.eval("scheme", "(define saved #f)");
            Value f = context.eval("scheme", "(lambda () (+ 1 (call/cc (lambda (k) (set! saved k) 1))))");
            assertEquals(2, f.execute().asInt());

            // The continuation is the rest of that first call: re-enter it, as often as you like.
            Value k = context.getBindings("scheme").getMember("saved");
            assertEquals(11, k.execute(10).asInt());
            assertEquals(21, k.execute(20).asInt());
            // readme-snippet-end
        }
    }

    @Test
    public void generator() {
        try (Context context = Context.create("scheme")) {
            // readme-snippet: generator
            Value next = context.eval("scheme", """
                            (define (make-generator items)
                              (define return #f)
                              (define resume #f)
                              (lambda ()
                                (call/cc
                                  (lambda (r)
                                    (set! return r)
                                    (if resume
                                        (resume #f)
                                        (begin
                                          (for-each (lambda (x)
                                                      (call/cc (lambda (k) (set! resume k) (return x))))
                                                    items)
                                          (return 'done)))))))
                            (make-generator '(a b))
                            """);
            assertEquals("a", next.execute().toString());
            assertEquals("b", next.execute().toString());
            assertEquals("done", next.execute().toString());
            // readme-snippet-end
        }
    }

    @Test
    public void polyglot() {
        // readme-snippet: polyglot
        Context.Builder builder = Context.newBuilder("scheme");
        builder.allowPolyglotAccess(PolyglotAccess.ALL); // polyglot-eval, polyglot-import and -export
        builder.allowHostAccess(HostAccess.ALL); // the members of Java objects
        builder.allowHostClassLookup(name -> name.startsWith("java.util.")); // what java-type may find
        try (Context context = builder.build()) {
            context.getPolyglotBindings().putMember("greeting", "Hello");
            Value program = context.eval("scheme", """
                            (import (scheme base) (graalvm polyglot))
                            (define names (foreign-new (java-type "java.util.ArrayList")))
                            (foreign-invoke names 'add "Ada")
                            (foreign-invoke names 'add "Grace")
                            (polyglot-export "greet"
                              (lambda (name) (string-append (polyglot-import "greeting") ", " name "!")))
                            (map (lambda (name) (string-length name)) (foreign->list names))
                            """);
            assertEquals("(3 5)", program.toString());
            Value greet = context.getPolyglotBindings().getMember("greet");
            assertEquals("Hello, Ada!", greet.execute("Ada").asString());

            // A Java lambda is a procedure to Scheme, and a Java exception is raised there.
            Value probe = context.eval("scheme", """
                            (import (scheme base) (graalvm polyglot))
                            (lambda (f items)
                              (list (f (foreign-invoke items 'get 0))
                                    (guard (e ((error-object? e) 'caught)) (foreign-invoke items 'get 5))))
                            """);
            Function<Object, String> describe = x -> "got " + x;
            assertEquals("(\"got 1\" caught)", probe.execute(describe, List.of(1, 2)).toString());
        }
        // readme-snippet-end
    }
}
