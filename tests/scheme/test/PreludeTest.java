package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.Test;

/** M2-01: the prelude is loaded into every context, and the dynamic environment (DynEnv) primitives. */
public class PreludeTest {

    private static final String SCHEME = "scheme";

    private static String output(String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            context.eval(SCHEME, program);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void preludeProceduresAreCallable() {
        assertEquals("(1 2 3)", output("(display (list 1 2 3))"));
        assertEquals("()", output("(display (list))"));
        assertEquals("2 3 (3) 1", output("(define l '(1 2 3)) (display (cadr l)) (display \" \") (display (caddr l))" +
                        "(display \" \") (display (cddr l)) (display \" \") (display (caar '((1))))"));
        assertEquals("3 (3 2 1) 0", output("(display (length '(1 2 3))) (display \" \") (display (reverse '(1 2 3)))" +
                        "(display \" \") (display (length '()))"));
        assertEquals("#<procedure list>", output("(display list)"));
    }

    @Test
    public void programsCanRedefinePreludeNames() {
        assertEquals("redefined", output("(define (list . xs) 'redefined) (display (list 1 2))"));
        // Each context loads its own prelude: the redefinition above doesn't leak.
        assertEquals("(1 2)", output("(display (list 1 2))"));
    }

    @Test
    public void preludeErrorsAreSchemeErrors() {
        try (Context context = Context.create(SCHEME)) {
            PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval(SCHEME, "(length 5)"));
            assertTrue(e.getMessage(), e.isGuestException());
        }
    }

    @Test
    public void dynamicEnvironment() {
        // The initial DynEnv has no winders, no parameterization, no handlers.
        assertEquals("() () ()", output("(define e (%current-dynamic-env))" +
                        "(display (%dynamic-env-winders e)) (display \" \")" +
                        "(display (%dynamic-env-parameterization e)) (display \" \") (display (%dynamic-env-handlers e))"));
        // Replacing the current DynEnv; the old record is unchanged (records are immutable).
        assertEquals("(w) (p) (h) () #t #f", output("(define old (%current-dynamic-env))" +
                        "(%set-dynamic-env! (%make-dynamic-env '(w) '(p) '(h)))" +
                        "(define now (%current-dynamic-env))" +
                        "(display (%dynamic-env-winders now)) (display \" \") (display (%dynamic-env-parameterization now))" +
                        "(display \" \") (display (%dynamic-env-handlers now)) (display \" \") (display (%dynamic-env-winders old))" +
                        "(display \" \") (display (%dynamic-env? now)) (display \" \") (display (%dynamic-env? '()))"));
        assertEquals("#<dynamic-environment>", output("(display (%current-dynamic-env))"));
        try (Context context = Context.create(SCHEME)) {
            PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval(SCHEME, "(%set-dynamic-env! 1)"));
            assertTrue(e.getMessage(), e.getMessage().contains("not a dynamic environment"));
        }
    }
}
