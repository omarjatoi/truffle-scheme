package scheme.runtime;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import org.graalvm.polyglot.SandboxPolicy;

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
    /**
     * Standard output's buffer (design.md D10, "Standard output"): {@link #flushOutput} writes it
     * out when control returns to the embedder, before a write to standard error or a read of
     * standard input, and when the context closes; {@code flush-output-port} and {@code exit} flush
     * it too.
     */
    private final OutputBuffer stdoutBuffer;
    private final PrintStream out;
    private SPort stdoutPort;
    private SPort stderrPort;
    private SPort stdinPort;
    /**
     * The open output file ports (design.md D10, "Open file ports"), written out with standard
     * output; null until the first is opened.
     */
    private OpenFiles openFiles;
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
    /**
     * In a native image, where the stack was when the root prompt started
     * ({@link StackBudget#stackPointer}): a call also spills when the stack below it is more than
     * {@link #stackLimit} deeper ({@link StackBudget#pastLimit}). 0 if not known.
     */
    long stackBase;
    /** Whether {@link #stackLimit} is final for this thread: measured, or fixed by an option. */
    boolean stackMeasured;
    /** Whether the {@code scheme.SpillDepth} option fixes the limit as a count of nested calls. */
    boolean spillDepthFixed;
    /**
     * The thread the context was created on, if {@code scheme.StackSize} gives its stack size, and
     * the limit that size allows there ({@link StackBudget}); null otherwise.
     */
    Thread sizedThread;
    int sizedThreadLimit;
    /** What a non-tail call adds to {@link #stackUse} ({@link StackBudget#callCost}). */
    int interpretedCallCost;
    int compiledCallCost;
    int compiledSlotCost;
    /**
     * The argument array of the call being made, from the call site (or trampoline) until the
     * callee's prologue releases it ({@link SchemeRootNode#releaseArguments}). Compiled code gets a
     * copy of the array (Truffle's argument type profile), while the caller's Java frames may keep
     * the original for as long as the callee runs: this is how the callee finds the original to
     * clear (design.md "Tail calls keep nothing alive"). Possibly stale: a primitive's call doesn't
     * release it, so a release checks that the array is its own.
     */
    Object[] callArguments;
    /** Stack-depth spills completed, for tests (plan.md M8-01). */
    public long spills;
    /** Measurements of a thread's stack ({@link StackBudget#remainingStack}), for tests. */
    public long stackProbes;
    /**
     * The sharing epoch (design.md "Owned segments"): a spill's segments are owned, resumed in
     * place, while their token holds the epoch this was when the root prompt took them. Bumped
     * ({@link #shareSegments}) whenever a continuation may come to reach a segment.
     */
    long sharingEpoch;
    /** Segments resumed in place (owned) and from a copy (shared), for tests. */
    public long inPlaceResumes;
    public long copiedResumes;
    /**
     * The capture a non-tail call site's {@code IsValue} found, for the {@code SuspendFrame} right
     * after it (SchemeRootNode): set and cleared with no Scheme code in between.
     */
    Capture suspending;
    /**
     * How many foreign operations (calls into another language or Java) are on the stack, for the
     * continuations captured and invoked on either side of them (design.md D20, {@link Foreign}).
     */
    int foreignDepth;
    /** One Scheme object per Java object that crosses in (design.md D10). */
    final HostObjects hostObjects = new HostObjects();
    /** Torture-mode captures completed (M2-06), for tests. */
    public long tortureCaptures;
    /** Where allocations go to listeners such as {@code --memtracer} ({@link Allocations}). */
    AllocationReporter allocationReporter;
    /** What the language added to {@link #allocationReporter}, removed when the context is disposed. */
    Consumer<Boolean> allocationListener;

    /**
     * Every segment that exists now is shared from here on: none of them is resumed in place again
     * (design.md "Owned segments", AGENTS.md rule 3).
     */
    void shareSegments() {
        sharingEpoch++;
    }

    SchemeContext(TruffleLanguage.Env env) {
        this.env = env;
        StackBudget.initialize(this, env.getOptions().get(SchemeLanguage.SPILL_DEPTH), env.getOptions().get(SchemeLanguage.STACK_SIZE));
        this.stdout = new ConsoleOutput(env.out(), null);
        this.stdoutBuffer = new OutputBuffer(stdout);
        this.stdoutBuffer.writeThrough = !env.getOptions().get(SchemeLanguage.BUFFER_OUTPUT);
        this.out = new PrintStream(stdoutBuffer, false, StandardCharsets.UTF_8);
        this.stderr = new ConsoleOutput(env.err(), this);
        this.stdin = new ConsoleInput(env.in(), this);
        this.allocationReporter = env.lookup(AllocationReporter.class);
    }

    /**
     * Writes what standard output holds to the Env's stream, and flushes that (design.md D10,
     * "Standard output"), and writes out what the open output file ports hold ("Open file ports").
     * Cheap when there is nothing to write and no file port is open.
     */
    public void flushOutput() {
        flushStandardOutput();
        if (openFiles != null && openFiles.count != 0) {
            openFiles.flush();
        }
    }

    /** Standard output only: before a write to standard error or a read of standard input. */
    private void flushStandardOutput() {
        if (!stdoutBuffer.isEmpty()) {
            flushBuffered();
        }
    }

    /** The open file ports: where a file port is made, so that it is tracked. */
    @TruffleBoundary
    public OpenFiles openFiles() {
        if (openFiles == null) {
            openFiles = new OpenFiles();
        }
        return openFiles;
    }

    /** Closes the file ports still open: the context is being disposed. */
    void closeFiles() {
        if (openFiles != null) {
            openFiles.close();
        }
    }

    @TruffleBoundary
    private void flushBuffered() {
        // PrintStream: an I/O error is kept for checkError, as for every write to standard output.
        out.flush();
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
        stdoutBuffer.writeThrough = !newEnv.getOptions().get(SchemeLanguage.BUFFER_OUTPUT);
        stdout.target = newEnv.out();
        stderr.target = newEnv.err();
        stdin.target = newEnv.in();
        this.allocationReporter = newEnv.lookup(AllocationReporter.class);
        // The run's options, and the run's threads: the stack measured so far was the image build's,
        // and the thread that takes the context over is the one scheme.StackSize describes.
        StackBudget.initialize(this, newEnv.getOptions().get(SchemeLanguage.SPILL_DEPTH), newEnv.getOptions().get(SchemeLanguage.STACK_SIZE));
    }

    public static SchemeContext get(Node node) {
        return REFERENCE.get(node);
    }

    public TruffleLanguage.Env getEnv() {
        return env;
    }

    /**
     * Whether the compiler builds its roots with every tag materialized: under
     * {@code SandboxPolicy.UNTRUSTED}, which requires {@code sandbox.MaxASTDepth}, a limit the
     * sandbox enforces on a Bytecode DSL root only once its tags are materialized (design.md D10,
     * "sandbox.MaxASTDepth under UNTRUSTED"; docs/upstream.md, "MaxASTDepth on Bytecode DSL roots").
     */
    public boolean materializesTags() {
        return env.getSandboxPolicy() == SandboxPolicy.UNTRUSTED;
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
            stdinPort = SPort.consoleInput(stdin);
        }
        return stdinPort;
    }

    /**
     * Standard output's buffer: a {@link BufferedOutputStream} that tells whether it holds anything,
     * and that writes each write through at once when {@link #writeThrough} (the launcher on a
     * terminal: {@code scheme.BufferOutput} false).
     */
    private static final class OutputBuffer extends BufferedOutputStream {
        boolean writeThrough;

        OutputBuffer(OutputStream out) {
            super(out);
        }

        /** Read on the context's thread, the only one that writes (D10: single-threaded). */
        boolean isEmpty() {
            return count == 0;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            super.write(b);
            if (writeThrough) {
                flush();
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            super.write(b, off, len);
            if (writeThrough) {
                flush();
            }
        }
    }

    /**
     * An Env's output stream, which {@link #patch} replaces. Standard error's flushes standard
     * output ({@code first}) before each write, so the two stay in order where they meet.
     */
    private static final class ConsoleOutput extends OutputStream {
        OutputStream target;
        private final SchemeContext first;

        ConsoleOutput(OutputStream target, SchemeContext first) {
            this.target = target;
            this.first = first;
        }

        @Override
        public void write(int b) throws IOException {
            if (first != null) {
                first.flushStandardOutput();
            }
            target.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (first != null) {
                first.flushStandardOutput();
            }
            target.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            target.flush();
        }
    }

    /**
     * An Env's input stream, which {@link #patch} replaces. It flushes standard output before it
     * reads, or tells what is available, so that a prompt shows before the program waits for its
     * answer. The console port reads it only when its own buffer runs out, which is when a read
     * may block.
     */
    private static final class ConsoleInput extends InputStream {
        InputStream target;
        private final SchemeContext context;

        ConsoleInput(InputStream target, SchemeContext context) {
            this.target = target;
            this.context = context;
        }

        @Override
        public int read() throws IOException {
            context.flushStandardOutput();
            return target.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            context.flushStandardOutput();
            return target.read(b, off, len);
        }

        @Override
        public int available() throws IOException {
            context.flushStandardOutput();
            return target.available();
        }
    }
}
