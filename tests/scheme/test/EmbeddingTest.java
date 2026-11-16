package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.Test;

/**
 * EXPLORATION (docs/interop-and-tools-exploration.md): embedding through InteropLibrary (design.md
 * D10). Scheme values answer interop as what they are, procedures are executable (each call under
 * a root prompt of its own), and the interaction environment is the language's top scope.
 */
public class EmbeddingTest {

    private static final String SCHEME = "scheme";

    @Test
    public void proceduresAreExecutable() {
        try (Context context = Context.create(SCHEME)) {
            Value square = context.eval(SCHEME, "(lambda (x) (* x x))");
            assertTrue(square.canExecute());
            assertEquals(49, square.execute(7).asInt());
            // Host arguments become Scheme values: Integer is an exact integer, Double a flonum.
            assertEquals(6.25, square.execute(2.5).asDouble(), 0);
            // Overflow into a big integer, which the host sees as a BigInteger.
            Value big = square.execute(Long.MAX_VALUE);
            assertTrue(big.fitsInBigInteger());
            assertFalse(big.fitsInLong());
            assertEquals(BigInteger.valueOf(Long.MAX_VALUE).pow(2), big.asBigInteger());
            // Named procedures have an executable name and a source location.
            Value add = context.eval(SCHEME, "(define (add a b) (+ a b)) add");
            assertEquals(5, add.execute(2, 3).asInt());
            assertEquals("#<procedure add>", add.toString());
            assertEquals(1, add.getSourceLocation().getStartLine());
            // Primitives too.
            assertEquals(3, context.eval(SCHEME, "car").execute(context.eval(SCHEME, "'(3 4)")).asInt());
        }
    }

    @Test
    public void valuesAnswerInteropAsWhatTheyAre() {
        try (Context context = Context.create(SCHEME)) {
            Value s = context.eval(SCHEME, "(string-append \"ab\" \"c\")");
            assertTrue(s.isString());
            assertEquals("abc", s.asString());
            Value c = context.eval(SCHEME, "#\\λ");
            assertTrue(c.isString());
            assertEquals("λ", c.asString());
            assertTrue(context.eval(SCHEME, "(< 1 2)").asBoolean());
            assertTrue(context.eval(SCHEME, "'()").isNull());
            assertTrue(context.eval(SCHEME, "(if #f #f)").isNull());
            // Symbols are not strings, but show as their name.
            Value sym = context.eval(SCHEME, "'hello");
            assertFalse(sym.isString());
            assertEquals("hello", sym.toString());
            // Vectors are arrays, and literal vectors are read-only.
            Value v = context.eval(SCHEME, "(vector 1 \"two\" 3.0)");
            assertTrue(v.hasArrayElements());
            assertEquals(3, v.getArraySize());
            assertEquals("two", v.getArrayElement(1).asString());
            v.setArrayElement(0, 42);
            assertEquals("#(42 \"two\" 3.0)", v.toString());
            Value literal = context.eval(SCHEME, "'#(1 2)");
            assertThrows(UnsupportedOperationException.class, () -> literal.setArrayElement(0, 5));
            // Bytevectors are arrays of bytes 0-255.
            assertEquals(255, context.eval(SCHEME, "(bytevector 1 255)").getArrayElement(1).asInt());
            // Lists are iterable.
            Value list = context.eval(SCHEME, "(list 1 2 3)");
            List<Integer> items = new ArrayList<>();
            Value it = list.getIterator();
            while (it.hasIteratorNextElement()) {
                items.add(it.getIteratorNextElement().asInt());
            }
            assertEquals(List.of(1, 2, 3), items);
            // Multiple values are an array.
            Value mv = context.eval(SCHEME, "(values 1 2)");
            assertEquals(2, mv.getArraySize());
            // Records are objects with their fields as members.
            Value point = context.eval(SCHEME, "(define-record-type point (make-point x y) point? (x point-x) (y point-y)) (make-point 3 4)");
            assertTrue(point.hasMembers());
            assertEquals(4, point.getMember("y").asInt());
            // Every value prints as write would.
            assertEquals("(1 \"a\" #\\b)", context.eval(SCHEME, "(list 1 \"a\" #\\b)").toString());
        }
    }

    @Test
    public void theInteractionEnvironmentIsTheTopScope() {
        try (Context context = Context.create(SCHEME)) {
            context.eval(SCHEME, "(define (greet name) (string-append \"hello, \" name))");
            Value bindings = context.getBindings(SCHEME);
            assertEquals("hello, world", bindings.getMember("greet").execute("world").asString());
            // Imported bindings are there too, but can't be assigned (design.md D7).
            assertTrue(bindings.hasMember("car"));
            assertTrue(bindings.getMemberKeys().contains("greet"));
            assertThrows(UnsupportedOperationException.class, () -> bindings.putMember("car", 1));
            // putMember defines, as (define limit 10) would.
            bindings.putMember("limit", 10);
            assertEquals(11, context.eval(SCHEME, "(+ limit 1)").asInt());
            // A host lambda put into the environment is foreign: it isn't a Scheme procedure.
            assertFalse(context.eval(SCHEME, "(procedure? limit)").asBoolean());
        }
    }

    @Test
    public void callsRunUnderTheirOwnPrompt() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            // A continuation captured inside a call is re-entered while the call runs.
            Value f = context.eval(SCHEME, """
                            (lambda (n)
                              (let ((k #f) (count 0))
                                (call/cc (lambda (c) (set! k c)))
                                (set! count (+ count 1))
                                (if (< count n) (k #f) count)))
                            """);
            assertEquals(5, f.execute(5).asInt());
            // Deep recursion spills under the call's own prompt.
            Value deep = context.eval(SCHEME, "(define (down n) (if (= n 0) 0 (+ 1 (down (- n 1))))) down");
            assertEquals(100000, deep.execute(100000).asInt());
            // Dynamic-wind and parameterize work inside.
            Value wind = context.eval(SCHEME, """
                            (lambda ()
                              (dynamic-wind (lambda () (display "in ")) (lambda () 'body) (lambda () (display "out"))))
                            """);
            assertEquals("body", wind.execute().toString());
            assertEquals("in out", out.toString(StandardCharsets.UTF_8));
            // An error the call doesn't handle is a guest exception with its location.
            Value bad = context.eval(SCHEME, "(lambda (x) (car x))");
            PolyglotException e = assertThrows(PolyglotException.class, () -> bad.execute(1));
            assertTrue(e.isGuestException());
            assertFalse(e.isInternalError());
            // A handler around it in Scheme sees it.
            assertEquals("caught", context.eval(SCHEME, "(guard (e (#t 'caught)) (car 1))").toString());
        }
    }
}
