package scheme.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.instrumentation.AllocationReporter;
import com.oracle.truffle.api.nodes.Node;

import scheme.runtime.value.SPort;
import scheme.runtime.value.SymbolTable;

/**
 * Per-context state: symbols (D1), globals (D7), and the console ports. All I/O goes through
 * {@link #getEnv()} (D10). A context pre-initialized in a native image takes the run's Env in
 * {@link #patch} (design.md D17).
 */
public final class SchemeContext {

    private static final ContextReference<SchemeContext> REFERENCE = ContextReference.create(SchemeLanguage.class);

    private TruffleLanguage.Env env;
    public final SymbolTable symbols = new SymbolTable();
    /** The primitives' namespace: the internal library (r7rs %primitives) (design.md D7). */
    public final GlobalEnvironment globals = new GlobalEnvironment();
    /**
     * The Env's standard streams, behind streams that {@link #patch} points at a new Env's: the
     * console ports read and write these, and the prelude's parameters hold those ports from the
     * start.
     */
    private final ConsoleOutput stdout;
    private final ConsoleOutput stderr;
    private final ConsoleInput stdin;
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
    /**
     * The prelude's {@code %unwind-root} (control.scm), installed by {@code %set-unwind-root!}: what
     * a root prompt runs the after-thunks through when an error leaves it (design.md D8).
     */
    public Object unwindRoot;
    private DynEnv dynamicEnv = DynEnv.INITIAL;
    /**
     * The Java stack, in bytes, that the non-tail calls nested since the root prompt are taken to use
     * (stack-depth spill, design.md "Continuations"): see {@link StackBudget}.
     */
    int stackUse;
    /** Past this {@link #stackUse}, a call spills instead ({@link StackBudget}). */
    int stackLimit;
    /** Whether {@link #stackLimit} is final for this thread: measured, or fixed by an option. */
    boolean stackMeasured;
    /** Whether the {@code scheme.SpillDepth} option fixes the limit as a count of nested calls. */
    boolean spillDepthFixed;
    /** What a non-tail call adds to {@link #stackUse} ({@link StackBudget#callCost}). */
    int interpretedCallCost;
    int compiledCallCost;
    int compiledSlotCost;
    /** Stack-depth spills completed, for tests (plan.md M8-01). */
    public long spills;
    /**
     * The capture a non-tail call site's {@code IsValue} found, for the {@code SuspendFrame} right
     * after it (SchemeRootNode): set and cleared with no Scheme code in between.
     */
    Capture suspending;
    /** One Scheme object per Java object that crosses in (design.md D10). */
    final HostObjects hostObjects = new HostObjects();
    /** Torture-mode captures completed (M2-06), for tests. */
    public long tortureCaptures;
    /** Where allocations go to listeners such as {@code --memtracer} ({@link Allocations}). */
    AllocationReporter allocationReporter;
    /** What the language added to {@link #allocationReporter}, removed when the context is disposed. */
    Consumer<Boolean> allocationListener;

    SchemeContext(TruffleLanguage.Env env) {
        this.env = env;
        StackBudget.initialize(this, env.getOptions().get(SchemeLanguage.SPILL_DEPTH));
        this.stdout = new ConsoleOutput(env.out());
        this.stderr = new ConsoleOutput(env.err());
        this.stdin = new ConsoleInput(env.in());
        this.out = new PrintStream(stdout, false, StandardCharsets.UTF_8);
        this.allocationReporter = env.lookup(AllocationReporter.class);
    }

    /**
     * Rebinds a context pre-initialized at image build time to the Env of the run that takes it
     * over ({@code SchemeLanguage.patchContext}, design.md D17). What else comes from the Env (the
     * command line, environment variables, files and the current directory, {@code exit}) is read
     * from {@link #getEnv()} when it is used, and the compiler rebinds the library path. The console
     * ports keep their identity, since the prelude's parameters hold them, and use the new streams:
     * pre-initialization reads and writes none, so nothing is left buffered.
     */
    @TruffleBoundary
    void patch(TruffleLanguage.Env newEnv) {
        out.flush();
        this.env = newEnv;
        stdout.target = newEnv.out();
        stderr.target = newEnv.err();
        stdin.target = newEnv.in();
        this.allocationReporter = newEnv.lookup(AllocationReporter.class);
        // The run's options, and the run's threads: the stack measured so far was the image build's.
        StackBudget.initialize(this, newEnv.getOptions().get(SchemeLanguage.SPILL_DEPTH));
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
            stderrPort = SPort.console(new PrintStream(stderr, true, StandardCharsets.UTF_8));
        }
        return stderrPort;
    }

    /** The console input port: the initial value of current-input-port. */
    @TruffleBoundary
    public SPort stdinPort() {
        if (stdinPort == null) {
            stdinPort = SPort.textInput(new InputStreamReader(stdin, StandardCharsets.UTF_8), true);
        }
        return stdinPort;
    }

    /** An Env's output stream, which {@link #patch} replaces. */
    private static final class ConsoleOutput extends OutputStream {
        OutputStream target;

        ConsoleOutput(OutputStream target) {
            this.target = target;
        }

        @Override
        public void write(int b) throws IOException {
            target.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            target.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            target.flush();
        }
    }

    /** An Env's input stream, which {@link #patch} replaces. */
    private static final class ConsoleInput extends InputStream {
        InputStream target;

        ConsoleInput(InputStream target) {
            this.target = target;
        }

        @Override
        public int read() throws IOException {
            return target.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return target.read(b, off, len);
        }

        @Override
        public int available() throws IOException {
            return target.available();
        }
    }
}
