package scheme.test;

import static org.junit.Assert.assertNotNull;

import org.junit.Test;

/**
 * M10-02: the native image's bytecode interpreter compiles each instruction's handler on its own
 * and threads them with tail calls (the Bytecode DSL's {@code enableTailCallHandlers}, Truffle's
 * "One Compilation per Bytecode Handler"; design.md D17, "The native interpreter"). The DSL
 * generates that interpreter as a cached tier of its own, which a native image uses
 * ({@code TruffleOptions.AOT}) and the JVM never does: on the JVM the host compiler inlines the
 * handlers into one {@code continueAt}, as before.
 */
public class TailCallHandlersTest {

    @Test
    public void theNativeImageHasAnInterpreterOfTailCalledHandlers() throws ClassNotFoundException {
        assertNotNull(Class.forName("scheme.runtime.SchemeRootNodeGen$CachedBytecodeNodeTailCall"));
    }
}
