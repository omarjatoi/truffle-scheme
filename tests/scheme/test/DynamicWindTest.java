package scheme.test;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.junit.Test;

/** M2-04: dynamic-wind and the single rewind(from, to) (design.md "Dynamic environment"). */
public class DynamicWindTest {

    private static final String SCHEME = "scheme";

    private static String output(String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            context.eval(SCHEME, program);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** R7RS §6.10, the dynamic-wind example, in core forms. */
    @Test
    public void r7rsExample() {
        assertEquals("(connect talk1 disconnect connect talk2 disconnect)", output(
                        "(define (example)" +
                                        "  (define path '())" +
                                        "  (define c #f)" +
                                        "  (define (add s) (set! path (cons s path)))" +
                                        "  (dynamic-wind" +
                                        "    (lambda () (add 'connect))" +
                                        "    (lambda () (add (call-with-current-continuation (lambda (c0) (set! c c0) 'talk1))))" +
                                        "    (lambda () (add 'disconnect)))" +
                                        "  (if (< (length path) 4) (c 'talk2) (reverse path)))" +
                                        "(display (example))"));
    }

    @Test
    public void escapesRunAfterThunks() {
        assertEquals("in body out 1", output("(display (call/cc (lambda (k) (dynamic-wind (lambda () (display \"in \"))" +
                        "  (lambda () (display \"body \") (k 1) (display \"never \")) (lambda () (display \"out \"))))))"));
        // Nested: innermost after-thunk first.
        assertEquals("a1 b1 b2 a2 done", output("(display (call/cc (lambda (k)" +
                        "  (dynamic-wind (lambda () (display \"a1 \")) (lambda ()" +
                        "    (dynamic-wind (lambda () (display \"b1 \")) (lambda () (k 'done)) (lambda () (display \"b2 \"))))" +
                        "   (lambda () (display \"a2 \"))))))"));
    }

    @Test
    public void reentryRunsBeforeThunksAgain() {
        // Re-entering two levels deep runs the before-thunks outermost first.
        assertEquals("a1 b1 b2 a2 | a1 b1 b2 a2 | ", output("(define (run)" +
                        "  (define k #f) (define n 0)" +
                        "  (dynamic-wind (lambda () (display \"a1 \")) (lambda ()" +
                        "    (dynamic-wind (lambda () (display \"b1 \")) (lambda () (call/cc (lambda (c) (set! k c))))" +
                        "                  (lambda () (display \"b2 \"))))" +
                        "    (lambda () (display \"a2 \")))" +
                        "  (display \"| \")" +
                        "  (set! n (+ n 1))" +
                        "  (if (< n 2) (k 'again) 'done))" +
                        "(run)"));
    }

    @Test
    public void valuesPassThrough() {
        assertEquals("42 #<values 1 2>", output("(display (dynamic-wind (lambda () 0) (lambda () 42) (lambda () 0))) (display \" \")" +
                        "(display (dynamic-wind (lambda () 0) (lambda () (call/cc (lambda (k) (k 1 2)))) (lambda () 0)))"));
    }

    @Test
    public void thunksRunInTheEnvironmentOutsideTheirWinder() {
        // Inside the body there is one winder; inside its before- and after-thunks there are none.
        assertEquals("0 1 0 0", output("(define (count) (length (%dynamic-env-winders (%current-dynamic-env))))" +
                        "(display (count)) (display \" \")" +
                        "(dynamic-wind (lambda () 0) (lambda () (display (count)) (display \" \")) (lambda () (display (count)) (display \" \")))" +
                        "(display (count))"));
    }

    @Test
    public void abortToPromptUnwinds() {
        assertEquals("in out 5", output("(display (%call-with-prompt 'p (lambda ()" +
                        "  (dynamic-wind (lambda () (display \"in \")) (lambda () (%capture-to-prompt 'p (lambda (k) 5))) (lambda () (display \"out \"))))))"));
    }

    @Test
    public void composableReentryWindsAndUnwinds() {
        // k includes the dynamic-wind; invoking it later winds in (in), runs, and winds out (out).
        assertEquals("in out | in out 11 0", output("(define kk #f)" +
                        "(%call-with-prompt 'p (lambda ()" +
                        "  (dynamic-wind (lambda () (display \"in \")) (lambda () (+ 1 (%capture-to-prompt 'p (lambda (k) (set! kk k) 0))))" +
                        "                (lambda () (display \"out \")))))" +
                        "(display \"| \")" +
                        "(display (kk 10))" +
                        "(display \" \") (display (length (%dynamic-env-winders (%current-dynamic-env))))"));
    }
}
