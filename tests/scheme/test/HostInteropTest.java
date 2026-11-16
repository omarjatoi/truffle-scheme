package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.Value;
import org.junit.Test;

/**
 * EXPLORATION (docs/interop-and-tools-exploration.md): Scheme code reaching Java through
 * {@code (truffle interop)}. Calls into Java are leaf calls; a Scheme procedure that Java calls back
 * runs under a root prompt of its own, and errors cross the Java frames both ways.
 */
public class HostInteropTest {

    private static final String SCHEME = "scheme";

    private static Context host(ByteArrayOutputStream out) {
        return Context.newBuilder(SCHEME).out(out).allowHostAccess(HostAccess.ALL).allowHostClassLookup(name -> name.startsWith("java.")).allowPolyglotAccess(PolyglotAccess.ALL).build();
    }

    private static String run(String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = host(out)) {
            context.eval(SCHEME, "(import (scheme base) (scheme write) (truffle interop))\n" + program);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void staticMembersInstancesAndConversions() {
        assertEquals("7 2147483647 #t\n", run("""
                        (define Math (java-type "java.lang.Math"))
                        (display (java-invoke Math "max" 3 7)) (display " ")
                        (display (java-field (java-type "java.lang.Integer") "MAX_VALUE")) (display " ")
                        ;; A Java String comes back as a Scheme string.
                        (display (string? (java-invoke (java-type "java.lang.String") "valueOf" 12)))
                        (newline)
                        """));
        assertEquals("\"abcd\" 4\n", run("""
                        (define sb (java-new (java-type "java.lang.StringBuilder") "ab"))
                        (java-invoke sb "append" "cd")
                        (write (java-invoke sb "toString")) (display " ")
                        (display (java-invoke sb "length"))
                        (newline)
                        """));
        // A vector is an array to Java (here, varargs), a list is not.
        assertEquals("a, b\n", run("""
                        (display (java-invoke (java-type "java.lang.String") "join" ", " (vector "a" "b")))
                        (newline)
                        """));
        // Foreign values print as their own language shows them.
        assertEquals("#t #f #<foreign [1, 2]>\n", run("""
                        (define l (java-new (java-type "java.util.ArrayList")))
                        (java-invoke l "add" 1) (java-invoke l "add" 2)
                        (display (foreign? l)) (display " ") (display (foreign? "x")) (display " ")
                        (display l)
                        (newline)
                        """));
    }

    @Test
    public void javaCallsSchemeBack() {
        assertEquals("6 #(3 2 1)\n", run("""
                        (define l (java-new (java-type "java.util.ArrayList")))
                        (for-each (lambda (x) (java-invoke l "add" x)) '(1 3 2))
                        ;; A Scheme procedure is a functional interface to Java (Consumer, Comparator).
                        (define sum 0)
                        (java-invoke l "forEach" (lambda (x) (set! sum (+ sum x))))
                        (java-invoke (java-type "java.util.Collections") "sort" l (lambda (a b) (- b a)))
                        (display sum) (display " ") (display (foreign->vector l))
                        (newline)
                        """));
        // call/cc inside a callback captures up to the callback's own prompt, and re-enters.
        assertEquals("(3 3 3)\n", run("""
                        (define l (java-new (java-type "java.util.ArrayList")))
                        (for-each (lambda (x) (java-invoke l "add" x)) '(1 2 3))
                        (define out '())
                        (java-invoke l "forEach"
                          (lambda (x)
                            (let ((n 0) (k #f))
                              (call/cc (lambda (c) (set! k c)))
                              (set! n (+ n 1))
                              (if (< n 3) (k #f) (set! out (cons n out))))))
                        (display out)
                        (newline)
                        """));
    }

    @Test
    public void continuationsEscapeThroughTheJavaFrames() {
        // An outer continuation invoked in a callback abandons the Java frames (an escape), and the
        // rest of the program runs once.
        assertEquals("2 after\n", run("""
                        (define l (java-new (java-type "java.util.ArrayList")))
                        (for-each (lambda (x) (java-invoke l "add" x)) '(1 2 3))
                        (define r (call/cc (lambda (k)
                                             (java-invoke l "forEach" (lambda (x) (if (= x 2) (k x))))
                                             'none)))
                        (display r)
                        (display " after")
                        (newline)
                        """));
        // The escape leaves a dynamic-wind around the Java call: its after thunk runs once.
        assertEquals("[in out] 1\n", run("""
                        (define l (java-new (java-type "java.util.ArrayList")))
                        (java-invoke l "add" 1)
                        (define r (call/cc (lambda (k)
                                             (dynamic-wind
                                               (lambda () (display "[in "))
                                               (lambda () (java-invoke l "forEach" (lambda (x) (k x))))
                                               (lambda () (display "out] "))))))
                        (display r)
                        (newline)
                        """));
    }

    @Test
    public void errorsCrossTheJavaFrames() {
        assertEquals("caught (boom) java\n", run("""
                        (define l (java-new (java-type "java.util.ArrayList")))
                        (java-invoke l "add" 1)
                        ;; A Scheme error in a callback reaches the guard outside the Java frames.
                        (display (guard (e ((error-object? e) 'caught)) (java-invoke l "forEach" (lambda (x) (car x)))))
                        (display " ")
                        ;; So does any raised object (wrapped, in this prototype).
                        (display (guard (e ((error-object? e) (error-object-irritants e)))
                                   (java-invoke l "forEach" (lambda (x) (raise 'boom)))))
                        (display " ")
                        ;; A Java exception is an error object whose irritant is the exception.
                        (display (guard (e ((error-object? e) 'java))
                                   (java-invoke (java-type "java.lang.Integer") "parseInt" "nope")))
                        (newline)
                        """));
    }

    @Test
    public void polyglotBindings() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = host(out)) {
            context.getPolyglotBindings().putMember("answer", 42);
            context.eval(SCHEME, """
                            (import (scheme base) (truffle interop))
                            (polyglot-export "double" (lambda (x) (* 2 (polyglot-import "answer"))))
                            """);
            Value doubled = context.getPolyglotBindings().getMember("double");
            assertEquals(84, doubled.execute(0).asInt());
        }
    }

    @Test
    public void hostAccessIsTheEmbeddersChoice() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            Value result = context.eval(SCHEME, """
                            (import (scheme base) (truffle interop))
                            (guard (e ((error-object? e) (error-object-message e)))
                              (java-type "java.lang.Math"))
                            """);
            assertTrue(result.asString(), result.asString().contains("not allowed"));
        }
    }
}
