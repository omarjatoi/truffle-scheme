package scheme.test;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.junit.Test;

/** M3-08: textual output ports (I/O area): the console port, string ports, current-output-port. */
public class PortsTest {

    private static String run(String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("scheme").out(out).build()) {
            context.eval("scheme", program);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void stringPorts() {
        assertEquals("\"(a \\\"b\\\" #\\\\c)x!\\n\"", run("""
                        (define p (open-output-string))
                        (write '(a "b" #\\c) p) (display "x" p) (write-char #\\! p) (newline p)
                        (write (get-output-string p))"""));
    }

    @Test
    public void currentOutputPortIsAParameter() {
        assertEquals("outside \"inside\"", run("""
                        (define p (open-output-string))
                        (parameterize ((current-output-port p)) (display "inside"))
                        (display "outside ") (write (get-output-string p))"""));
        assertEquals("abc", run("(write-string \"abc\" (current-output-port)) (flush-output-port)"));
        assertEquals("(#t #t #f)", run("(write (list (output-port? (current-output-port)) (textual-port? (open-output-string)) (input-port? 1)))"));
    }
}
