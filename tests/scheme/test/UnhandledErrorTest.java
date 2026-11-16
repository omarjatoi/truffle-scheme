package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.Test;

/**
 * M7-06: what an error that no handler catches does when it leaves a root prompt (an embedder's
 * call, {@code Context.eval}, a REPL input): the after-thunks it skips run first (design.md D8),
 * and the embedder gets the raised object as the exception's guest object (D10).
 */
public class UnhandledErrorTest {

    private static final String SCHEME = "scheme";

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private Context context() {
        return Context.newBuilder(SCHEME).out(out).build();
    }

    private static Value eval(Context context, String text) {
        return context.eval(SCHEME, text);
    }

    /** What was written to stdout since the last call. */
    private String output() {
        String text = out.toString(StandardCharsets.UTF_8);
        out.reset();
        return text;
    }

    /** Two winders, each displaying a word when control leaves it, around body. */
    private static String nested(String body) {
        return "(dynamic-wind (lambda () #f) (lambda () (dynamic-wind (lambda () #f) (lambda () " + body + ") (lambda () (display \"inner \")))) (lambda () (display \"outer\")))";
    }

    @Test
    public void afterThunksRunWhenAnErrorLeavesACall() {
        try (Context context = context()) {
            Value f = eval(context, "(lambda () " + nested("(car 1)") + ")");
            PolyglotException e = assertThrows(PolyglotException.class, f::execute);
            assertTrue(e.getMessage(), e.getMessage().contains("car"));
            assertFalse(e.isInternalError());
            assertEquals("inner outer", output());
            // Raised from deep inside, past frames spilled to the heap, and through a handler.
            Value deep = eval(context, "(lambda (n) (let loop ((n n)) (if (= n 0) " + nested("(raise 'deep)") + " (+ 1 (loop (- n 1))))))");
            e = assertThrows(PolyglotException.class, () -> deep.execute(100000));
            assertTrue(e.getMessage(), e.getMessage().contains("deep"));
            assertEquals("inner outer", output());
            Value handled = eval(context, "(lambda () (with-exception-handler (lambda (c) (display \"handler \") c) (lambda () " + nested("(raise 'x)") + ")))");
            assertThrows(PolyglotException.class, handled::execute);
            assertEquals("handler inner outer", output());
        }
    }

    @Test
    public void eachAfterThunkRunsInItsOwnDynamicEnvironment() {
        try (Context context = context()) {
            eval(context, "(define p (make-parameter 0))");
            Value f = eval(context, """
                            (lambda ()
                              (parameterize ((p 1))
                                (dynamic-wind
                                  (lambda () #f)
                                  (lambda () (parameterize ((p 2)) (dynamic-wind (lambda () #f) (lambda () (raise 'x)) (lambda () (display (p))))))
                                  (lambda () (display (p))))))
                            """);
            assertThrows(PolyglotException.class, f::execute);
            assertEquals("21", output());
            assertEquals(0, eval(context, "(p)").asInt());
        }
    }

    @Test
    public void afterThunksRunWhenAnErrorLeavesContextEvalOrAReplInput() {
        try (Context context = context()) {
            assertThrows(PolyglotException.class, () -> eval(context, nested("(error \"bad\")")));
            assertEquals("inner outer", output());
            Source input = Source.newBuilder(SCHEME, nested("(vector-ref (vector) 0)"), "<repl>").interactive(true).buildLiteral();
            assertThrows(PolyglotException.class, () -> context.eval(input));
            assertEquals("inner outer", output());
            // A program too: its prompt is a root prompt like the others.
            assertThrows(PolyglotException.class, () -> eval(context, "(import (scheme base) (scheme write)) " + nested("(raise 'program)")));
            assertEquals("inner outer", output());
        }
    }

    @Test
    public void anErrorInAnAfterThunkReplacesTheOriginal() {
        try (Context context = context()) {
            Value f = eval(context, "(lambda () " + nested("(dynamic-wind (lambda () #f) (lambda () (raise 'first)) (lambda () (display \"third \") (raise 'second)))") + ")");
            PolyglotException e = assertThrows(PolyglotException.class, f::execute);
            assertTrue(e.getMessage(), e.getMessage().contains("second"));
            assertFalse(e.getMessage(), e.getMessage().contains("first"));
            // The outer after-thunks still run: the second error leaves them too.
            assertEquals("third inner outer", output());
        }
    }

    @Test
    public void anAfterThunkCanTransferControl() {
        try (Context context = context()) {
            // It escapes to a continuation of the call: the call then returns normally.
            Value escape = eval(context, "(lambda () (call/cc (lambda (k) (dynamic-wind (lambda () #f) (lambda () (raise 'lost)) (lambda () (k 'escaped))))))");
            assertEquals("escaped", escape.execute().toString());
            Value later = eval(context, "(lambda () (list 'got (call/cc (lambda (k) " + nested("(dynamic-wind (lambda () #f) (lambda () (car 1)) (lambda () (k 'back)))") + "))))");
            assertEquals("(got back)", later.execute().toString());
            assertEquals("inner outer", output());
        }
    }

    @Test
    public void anAfterThunkCanExit() {
        // The after-thunks outside it run first, as exit's do.
        try (Context context = context()) {
            Value exit = eval(context, "(lambda () " + nested("(dynamic-wind (lambda () #f) (lambda () (raise 'x)) (lambda () (exit 3)))") + ")");
            exit.execute();
            fail("exit returned");
        } catch (PolyglotException e) {
            assertTrue(e.isExit());
            assertEquals(3, e.getExitStatus());
        }
        assertEquals("inner outer", output());
    }

    @Test
    public void nothingToUnwindLeavesTheErrorAsItIs() {
        try (Context context = context()) {
            PolyglotException e = assertThrows(PolyglotException.class, () -> eval(context, "(car '())"));
            assertTrue(e.getMessage(), e.getMessage().contains("car"));
            e = assertThrows(PolyglotException.class, () -> eval(context, "(raise 'x)"));
            assertTrue(e.getMessage(), e.getMessage().contains("x"));
            assertEquals("", output());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The raised object

    private static PolyglotException error(Context context, String expr) {
        return assertThrows(PolyglotException.class, () -> eval(context, "(lambda () " + expr + ")").execute());
    }

    private static List<String> elements(Value iterable) {
        List<String> elements = new ArrayList<>();
        Value iterator = iterable.getIterator();
        while (iterator.hasIteratorNextElement()) {
            elements.add(iterator.getIteratorNextElement().toString());
        }
        return elements;
    }

    @Test
    public void theGuestObjectIsTheRaisedObject() {
        try (Context context = context()) {
            Value number = error(context, "(raise 42)").getGuestObject();
            assertTrue(number.isException());
            assertTrue(number.isNumber());
            assertEquals(42, number.asInt());
            assertEquals("42", number.toString());
            Value symbol = error(context, "(raise 'boom)").getGuestObject();
            assertEquals("boom", symbol.toString());
            assertEquals("symbol", symbol.getMetaObject().getMetaSimpleName());
            Value list = error(context, "(raise (list 1 \"two\"))").getGuestObject();
            assertEquals(List.of("1", "\"two\""), elements(list));
            Value string = error(context, "(raise \"text\")").getGuestObject();
            assertTrue(string.isString());
            assertEquals("text", string.asString());
            // The same object goes back into Scheme.
            Value raised = eval(context, "(define raised (list 'x)) (lambda () (raise raised))");
            Value same = eval(context, "(lambda (e) (eq? e raised))");
            assertTrue(same.execute(assertThrows(PolyglotException.class, raised::execute).getGuestObject()).asBoolean());
        }
    }

    @Test
    public void anErrorObjectHasItsMessageAndIrritantsAsMembers() {
        try (Context context = context()) {
            PolyglotException e = error(context, "(error \"bad thing:\" 1 'x)");
            assertTrue(e.getMessage(), e.getMessage().contains("bad thing: 1 x"));
            Value error = e.getGuestObject();
            assertTrue(error.isException());
            assertEquals("error-object", error.getMetaObject().getMetaSimpleName());
            assertTrue(error.hasMember("message"));
            assertEquals("bad thing:", error.getMember("message").asString());
            assertEquals(List.of("1", "x"), elements(error.getMember("irritants")));
            assertTrue(error.getMemberKeys().containsAll(List.of("message", "irritants")));
            // A primitive's error, uncaught.
            Value primitive = error(context, "(car 1)").getGuestObject();
            assertTrue(primitive.getMember("message").asString(), primitive.getMember("message").asString().contains("car"));
            assertTrue(eval(context, "error-object?").execute(primitive).asBoolean());
            // An error object raised, caught by Java, and handled in Scheme again.
            assertEquals("bad thing:", eval(context, "error-object-message").execute(error).asString());
            // One that Java code raises and nothing in Scheme handles (a call from Java with the wrong arity).
            Value arity = assertThrows(PolyglotException.class, () -> eval(context, "car").execute(1, 2)).getGuestObject();
            assertTrue(arity.hasMember("message"));
        }
    }

    @Test
    public void aSyntaxErrorIsAnErrorObjectToo() {
        try (Context context = context()) {
            PolyglotException e = assertThrows(PolyglotException.class, () -> eval(context, "(if)"));
            assertTrue(e.isSyntaxError());
            Value error = e.getGuestObject();
            assertTrue(error.isException());
            assertEquals(e.getMessage(), error.getMember("message").asString());
        }
    }
}
