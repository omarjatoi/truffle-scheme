package scheme.test;

import static org.junit.Assert.assertNotEquals;

import org.graalvm.polyglot.Engine;
import org.junit.Test;

/** The Graal JIT must actually be in use, not Truffle's interpreter-only fallback runtime. */
public class RuntimeTest {

    /** {@link Engine#getImplementationName()} of Truffle's fallback runtime. */
    private static final String FALLBACK_RUNTIME = "Interpreted";

    @Test
    public void optimizingRuntimeIsActive() {
        try (Engine engine = Engine.create()) {
            assertNotEquals("Truffle fell back to the interpreter-only runtime; is TRUFFLE_RUNTIME on the module path?",
                            FALLBACK_RUNTIME, engine.getImplementationName());
        }
    }
}
