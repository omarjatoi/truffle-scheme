package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.Test;

/** M0-01: the language evaluates through the polyglot API; Scheme errors are guest errors. */
public class StubLanguageTest {

    private static final String SCHEME = "scheme";

    @Test
    public void evaluatesIntegerLiteral() {
        try (Context context = Context.create(SCHEME)) {
            Value value = context.eval(SCHEME, "42");
            assertTrue(value.fitsInLong());
            assertEquals(42L, value.asLong());
        }
    }

    @Test
    public void evaluatesNegativeLiteralWithSurroundingWhitespace() {
        try (Context context = Context.create(SCHEME)) {
            assertEquals(-7L, context.eval(SCHEME, "  -7\n").asLong());
        }
    }

    @Test
    public void evaluatesRepeatedly() {
        // Enough calls for the JIT to kick in under background compilation too.
        try (Context context = Context.create(SCHEME)) {
            Value program = context.parse(SCHEME, "123");
            for (int i = 0; i < 10_000; i++) {
                assertEquals(123L, program.execute().asLong());
            }
        }
    }

    @Test
    public void schemeErrorsAreGuestErrors() {
        try (Context context = Context.create(SCHEME)) {
            PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval(SCHEME, "(car 1)"));
            assertTrue(e.isGuestException());
        }
    }
}
