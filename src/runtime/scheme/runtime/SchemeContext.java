package scheme.runtime;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.nodes.Node;

import scheme.runtime.value.SymbolTable;

/** Per-context state: symbols (D1), globals (D7), and the output port. All I/O goes through {@link #getEnv()} (D10). */
public final class SchemeContext {

    private static final ContextReference<SchemeContext> REFERENCE = ContextReference.create(SchemeLanguage.class);

    private final TruffleLanguage.Env env;
    public final SymbolTable symbols = new SymbolTable();
    public final GlobalEnvironment globals = new GlobalEnvironment();
    private final PrintStream out;
    private DynEnv dynamicEnv = DynEnv.INITIAL;
    /** Nested non-tail calls since the root prompt (stack-depth spill, design.md "Continuations"). */
    public int callDepth;
    /** Torture-mode captures completed (M2-06), for tests. */
    public long tortureCaptures;

    SchemeContext(TruffleLanguage.Env env) {
        this.env = env;
        this.out = new PrintStream(env.out(), false, StandardCharsets.UTF_8);
    }

    public static SchemeContext get(Node node) {
        return REFERENCE.get(node);
    }

    public TruffleLanguage.Env getEnv() {
        return env;
    }

    /** The current dynamic environment (design.md "Dynamic environment"). */
    public DynEnv getDynamicEnv() {
        return dynamicEnv;
    }

    public void setDynamicEnv(DynEnv dynamicEnv) {
        this.dynamicEnv = dynamicEnv;
    }

    /** The current output port (until ports arrive in M4). */
    public PrintStream out() {
        return out;
    }
}
