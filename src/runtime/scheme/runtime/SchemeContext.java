package scheme.runtime;

import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.nodes.Node;

import scheme.runtime.value.SPort;
import scheme.runtime.value.SymbolTable;

/** Per-context state: symbols (D1), globals (D7), and the console ports. All I/O goes through {@link #getEnv()} (D10). */
public final class SchemeContext {

    private static final ContextReference<SchemeContext> REFERENCE = ContextReference.create(SchemeLanguage.class);

    private final TruffleLanguage.Env env;
    public final SymbolTable symbols = new SymbolTable();
    /** The primitives' namespace: the internal library (r7rs %primitives) (design.md D7). */
    public final GlobalEnvironment globals = new GlobalEnvironment();
    private final PrintStream out;
    private SPort stdoutPort;
    private SPort stderrPort;
    private SPort stdinPort;
    /**
     * The compiler's per-context state: libraries, the interaction environment and syntactic
     * bindings, which persist from one program to the next. Owned by the compiler
     * ({@code scheme.compiler.CompilerState}), which sits above the runtime.
     */
    public Object compilerState;
    /** The prelude's {@code %receive} (control.scm), installed by {@code %set-receive!}. */
    public Object receive;
    /** The prelude's {@code %escape} (control.scm), installed by {@code %set-escape!} (M5-04). */
    public Object escape;
    /** The prelude's {@code raise} (exceptions.scm), installed by {@code %set-raise!} (design.md D8). */
    public Object raise;
    private DynEnv dynamicEnv = DynEnv.INITIAL;
    /** Nested non-tail calls since the root prompt (stack-depth spill, design.md "Continuations"). */
    public int callDepth;
    /** One Scheme object per Java object that crosses in (design.md D10). */
    final HostObjects hostObjects = new HostObjects();
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

    /** The context's standard output, as a stream. */
    public PrintStream out() {
        return out;
    }

    /** The console output port: the initial value of current-output-port. */
    public SPort stdoutPort() {
        if (stdoutPort == null) {
            stdoutPort = SPort.console(out);
        }
        return stdoutPort;
    }

    /** The console error port: the initial value of current-error-port. */
    @TruffleBoundary
    public SPort stderrPort() {
        if (stderrPort == null) {
            stderrPort = SPort.console(new PrintStream(env.err(), true, StandardCharsets.UTF_8));
        }
        return stderrPort;
    }

    /** The console input port: the initial value of current-input-port. */
    @TruffleBoundary
    public SPort stdinPort() {
        if (stdinPort == null) {
            stdinPort = SPort.textInput(new InputStreamReader(env.in(), StandardCharsets.UTF_8), true);
        }
        return stdinPort;
    }
}
