package scheme.builtins;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.TruffleLanguage;

import scheme.reader.CharSource;
import scheme.reader.Reader;
import scheme.runtime.PrimitiveNode;
import scheme.runtime.PrimitiveSpec;
import scheme.runtime.PrimitiveSpec.Result;
import scheme.runtime.SchemeContext;
import scheme.runtime.SchemeError;
import scheme.runtime.printer.Printer;
import scheme.runtime.value.MultipleValues;
import scheme.runtime.value.SBytevector;
import scheme.runtime.value.SChar;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SErrorObject;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SPort;
import scheme.runtime.value.SString;

/**
 * The input and output area (R7RS §6.13): its primitives, all leaves (AGENTS.md rule 1). One of the
 * fan-out areas (AGENTS.md "Areas"): add this area's primitives here and nowhere else. The
 * {@code %}-primitives take every argument explicitly; io.scm supplies the current ports and the
 * default ranges when they are left out, and the procedures that call Scheme
 * ({@code call-with-port}, {@code with-input-from-file}, ...).
 *
 * <p>File ports go through {@link TruffleFile} (design.md D10). A file that can't be opened, read or
 * written is a {@code file-error?} (design.md D8); {@code read} raises the reader's errors, which are
 * {@code read-error?}s.
 */
final class IoPrimitives {

    private IoPrimitives() {
    }

    /** This area's primitives, as {@code Builtins} registers them. */
    static List<PrimitiveSpec> primitives() {
        List<PrimitiveSpec> specs = new ArrayList<>();
        for (Primitive primitive : Primitive.values()) {
            specs.add(primitive.spec);
        }
        return specs;
    }

    /**
     * This area's primitives: the one list to add to. A constant has the primitive's name and
     * arity, and {@link #node} makes its node when it is first called: no lambda per primitive for
     * the JVM to link as a context starts (design.md D17, "The primitives' registry").
     */
    enum Primitive implements Supplier<PrimitiveNode> {
        // Console ports, the initial values of the current-port parameters.
        STDOUT_PORT("%stdout-port", 0, 0),
        STDERR_PORT("%stderr-port", 0, 0),
        STDIN_PORT("%stdin-port", 0, 0),
        // Predicates and closing.
        IS_PORT("port?", 1, 1),
        IS_INPUT_PORT("input-port?", 1, 1),
        IS_OUTPUT_PORT("output-port?", 1, 1),
        IS_TEXTUAL_PORT("textual-port?", 1, 1),
        IS_BINARY_PORT("binary-port?", 1, 1),
        IS_INPUT_PORT_OPEN("input-port-open?", 1, 1),
        IS_OUTPUT_PORT_OPEN("output-port-open?", 1, 1),
        CLOSE_PORT("close-port", 1, 1),
        CLOSE_INPUT_PORT("close-input-port", 1, 1),
        CLOSE_OUTPUT_PORT("close-output-port", 1, 1),
        EOF_OBJECT("eof-object", 0, 0),
        IS_EOF_OBJECT("eof-object?", 1, 1),
        // String and bytevector ports.
        OPEN_INPUT_STRING("open-input-string", 1, 1, Result.NEW_OBJECT),
        OPEN_OUTPUT_STRING("open-output-string", 0, 0, Result.NEW_OBJECT),
        GET_OUTPUT_STRING("get-output-string", 1, 1, Result.NEW_OBJECT),
        OPEN_INPUT_BYTEVECTOR("open-input-bytevector", 1, 1, Result.NEW_OBJECT),
        OPEN_OUTPUT_BYTEVECTOR("open-output-bytevector", 0, 0, Result.NEW_OBJECT),
        GET_OUTPUT_BYTEVECTOR("get-output-bytevector", 1, 1, Result.NEW_OBJECT),
        // File ports (R7RS §6.13.1, (scheme file)).
        OPEN_INPUT_FILE("open-input-file", 1, 1),
        OPEN_BINARY_INPUT_FILE("open-binary-input-file", 1, 1),
        OPEN_OUTPUT_FILE("open-output-file", 1, 1),
        OPEN_BINARY_OUTPUT_FILE("open-binary-output-file", 1, 1),
        IS_FILE_EXISTS("file-exists?", 1, 1),
        DELETE_FILE("delete-file", 1, 1),
        // Input (R7RS §6.13.2): (%op [k] port).
        READ("%read", 1, 1),
        READ_CHAR("%read-char", 1, 1),
        PEEK_CHAR("%peek-char", 1, 1),
        READ_LINE("%read-line", 1, 1),
        IS_CHAR_READY("%char-ready?", 1, 1),
        READ_STRING("%read-string", 2, 2),
        READ_U8("%read-u8", 1, 1),
        PEEK_U8("%peek-u8", 1, 1),
        IS_U8_READY("%u8-ready?", 1, 1),
        READ_BYTEVECTOR("%read-bytevector", 2, 2),
        READ_BYTEVECTOR_INTO("%read-bytevector!", 2, 4),
        // Output (R7RS §6.13.3): (%op obj port [start [end]]).
        DISPLAY("%display", 2, 2),
        WRITE("%write", 2, 2),
        WRITE_SHARED("%write-shared", 2, 2),
        WRITE_SIMPLE("%write-simple", 2, 2),
        WRITE_CHAR("%write-char", 2, 2),
        WRITE_STRING("%write-string", 2, 4),
        WRITE_U8("%write-u8", 2, 2),
        WRITE_BYTEVECTOR("%write-bytevector", 2, 4),
        FLUSH_OUTPUT_PORT("%flush-output-port", 1, 1),
        // The REPL's echo of each top-level form's values (design.md D17).
        REPL_WRITE("%repl-write", 1, 1);

        final PrimitiveSpec spec;

        Primitive(String name, int minArgs, int maxArgs) {
            this(name, minArgs, maxArgs, Result.VALUE);
        }

        Primitive(String name, int minArgs, int maxArgs, Result result) {
            this.spec = new PrimitiveSpec(name, minArgs, maxArgs, this, result);
        }

        @Override
        public PrimitiveNode get() {
            return (PrimitiveNode) node();
        }

        /**
         * The primitive's node: a {@code return} in each case, typed {@code Object}, so that the
         * JVM's verifier needn't load every node class to check what is returned (a switch
         * expression's cases would join as {@code PrimitiveNode}). Each is loaded when it is made.
         */
        private Object node() {
            switch (this) {
                case STDOUT_PORT:
                    return new ConsolePort(ConsolePort.OUT);
                case STDERR_PORT:
                    return new ConsolePort(ConsolePort.ERR);
                case STDIN_PORT:
                    return new ConsolePort(ConsolePort.IN);
                case IS_PORT:
                    return new TypePredicate(x -> x instanceof SPort);
                case IS_INPUT_PORT:
                    return new TypePredicate(x -> x instanceof SPort p && p.isInput());
                case IS_OUTPUT_PORT:
                    return new TypePredicate(x -> x instanceof SPort p && p.isOutput());
                case IS_TEXTUAL_PORT:
                    return new TypePredicate(x -> x instanceof SPort p && p.isTextual());
                case IS_BINARY_PORT:
                    return new TypePredicate(x -> x instanceof SPort p && p.isBinary());
                case IS_INPUT_PORT_OPEN:
                    return new PortOpen(true);
                case IS_OUTPUT_PORT_OPEN:
                    return new PortOpen(false);
                case CLOSE_PORT:
                    return new ClosePort("close-port");
                case CLOSE_INPUT_PORT:
                    return new ClosePort("close-input-port");
                case CLOSE_OUTPUT_PORT:
                    return new ClosePort("close-output-port");
                case EOF_OBJECT:
                    return new EofObject();
                case IS_EOF_OBJECT:
                    return new TypePredicate(x -> x == SConstant.EOF);
                case OPEN_INPUT_STRING:
                    return new OpenInputString();
                case OPEN_OUTPUT_STRING:
                    return new OpenOutputString();
                case GET_OUTPUT_STRING:
                    return new GetOutputString();
                case OPEN_INPUT_BYTEVECTOR:
                    return new OpenInputBytevector();
                case OPEN_OUTPUT_BYTEVECTOR:
                    return new OpenOutputBytevector();
                case GET_OUTPUT_BYTEVECTOR:
                    return new GetOutputBytevector();
                case OPEN_INPUT_FILE:
                    return new OpenFile(OpenFile.TEXT_IN);
                case OPEN_BINARY_INPUT_FILE:
                    return new OpenFile(OpenFile.BINARY_IN);
                case OPEN_OUTPUT_FILE:
                    return new OpenFile(OpenFile.TEXT_OUT);
                case OPEN_BINARY_OUTPUT_FILE:
                    return new OpenFile(OpenFile.BINARY_OUT);
                case IS_FILE_EXISTS:
                    return new FileOp(FileOp.EXISTS);
                case DELETE_FILE:
                    return new FileOp(FileOp.DELETE);
                case READ:
                    return new Read();
                case READ_CHAR:
                    return new ReadChar(false);
                case PEEK_CHAR:
                    return new ReadChar(true);
                case READ_LINE:
                    return new ReadLine();
                case IS_CHAR_READY:
                    return new Ready(false);
                case READ_STRING:
                    return new ReadString();
                case READ_U8:
                    return new ReadU8(false);
                case PEEK_U8:
                    return new ReadU8(true);
                case IS_U8_READY:
                    return new Ready(true);
                case READ_BYTEVECTOR:
                    return new ReadBytevector();
                case READ_BYTEVECTOR_INTO:
                    return new ReadBytevectorInto();
                case DISPLAY:
                    return new Print(Printer.Mode.DISPLAY, "display");
                case WRITE:
                    return new Print(Printer.Mode.WRITE, "write");
                case WRITE_SHARED:
                    return new Print(Printer.Mode.WRITE_SHARED, "write-shared");
                case WRITE_SIMPLE:
                    return new Print(Printer.Mode.WRITE_SIMPLE, "write-simple");
                case WRITE_CHAR:
                    return new WriteChar();
                case WRITE_STRING:
                    return new WriteString();
                case WRITE_U8:
                    return new WriteU8();
                case WRITE_BYTEVECTOR:
                    return new WriteBytevector();
                case FLUSH_OUTPUT_PORT:
                    return new Flush();
                case REPL_WRITE:
                    return new ReplWrite();
            }
            throw new AssertionError(this);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Argument checks

    /** An open port that is an input (or output) port, and binary (or textual). */
    private static SPort port(String who, Object x, boolean input, boolean binary) {
        if (x instanceof SPort p && p.isInput() == input && p.isBinary() == binary) {
            if (!p.isOpen()) {
                throw closed(who, p);
            }
            return p;
        }
        throw notAPort(who, x, input, binary);
    }

    @TruffleBoundary
    private static SchemeError notAPort(String who, Object x, boolean input, boolean binary) {
        return ControlPrimitives.notA(who, (binary ? "binary " : "textual ") + (input ? "input" : "output") + " port", x);
    }

    private static SPort textualInput(String who, Object x) {
        return port(who, x, true, false);
    }

    private static SPort textualOutput(String who, Object x) {
        return port(who, x, false, false);
    }

    private static SPort binaryInput(String who, Object x) {
        return port(who, x, true, true);
    }

    private static SPort binaryOutput(String who, Object x) {
        return port(who, x, false, true);
    }

    @TruffleBoundary
    private static SchemeError closed(String who, SPort port) {
        return new SchemeError(who + ": port is closed: " + Printer.print(port, Printer.Mode.WRITE));
    }

    /** A non-negative fixnum count or index no greater than {@code max}. */
    private static int index(String who, Object x, int min, int max) {
        if (x instanceof Long l && l >= min && l <= max) {
            return l.intValue();
        }
        throw outOfRange(who, x, min, max);
    }

    @TruffleBoundary
    private static SchemeError outOfRange(String who, Object x, int min, int max) {
        if (!(x instanceof Long)) {
            return ControlPrimitives.notA(who, "exact integer", x);
        }
        return new SchemeError(who + ": " + x + " is out of range [" + min + ", " + max + "]");
    }

    private static SString string(String who, Object x) {
        if (x instanceof SString s) {
            return s;
        }
        throw ControlPrimitives.notA(who, "string", x);
    }

    private static SBytevector bytevector(String who, Object x) {
        if (x instanceof SBytevector b) {
            return b;
        }
        throw ControlPrimitives.notA(who, "bytevector", x);
    }

    @TruffleBoundary
    static SchemeError fileError(String message, Object irritant) {
        return new SchemeError(new SErrorObject(SString.of(message), new SPair(irritant, SConstant.EMPTY_LIST), SErrorObject.Kind.FILE));
    }

    // ------------------------------------------------------------------------------------------
    // Ports, predicates, closing

    static final class ConsolePort extends PrimitiveNode {
        static final int OUT = 0;
        static final int ERR = 1;
        static final int IN = 2;
        private final int which;

        ConsolePort(int which) {
            this.which = which;
        }

        @Override
        public Object invoke(Object[] args) {
            SchemeContext context = SchemeContext.get(this);
            return switch (which) {
                case OUT -> context.stdoutPort();
                case ERR -> context.stderrPort();
                default -> context.stdinPort();
            };
        }
    }

    /** {@code input-port-open?}, {@code output-port-open?}. */
    static final class PortOpen extends PrimitiveNode {
        private final boolean input;

        PortOpen(boolean input) {
            this.input = input;
        }

        @Override
        public Object invoke(Object[] args) {
            if (!(args[1] instanceof SPort p)) {
                throw ControlPrimitives.notA(input ? "input-port-open?" : "output-port-open?", "port", args[1]);
            }
            return p.isOpen() && p.isInput() == input;
        }
    }

    static final class ClosePort extends PrimitiveNode {
        private final String who;

        ClosePort(String who) {
            this.who = who;
        }

        @Override
        public Object invoke(Object[] args) {
            if (!(args[1] instanceof SPort p)) {
                throw ControlPrimitives.notA(who, "port", args[1]);
            }
            p.close();
            return SConstant.UNSPECIFIED;
        }
    }

    static final class EofObject extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return SConstant.EOF;
        }
    }

    static final class OpenInputString extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return open(string("open-input-string", args[1]));
        }

        @TruffleBoundary
        private static SPort open(SString s) {
            return SPort.inputString(s.toString());
        }
    }

    static final class OpenOutputString extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return SPort.outputString();
        }
    }

    static final class GetOutputString extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            if (!(args[1] instanceof SPort p) || !p.isStringPort()) {
                throw ControlPrimitives.notA("get-output-string", "string output port", args[1]);
            }
            return contents(p);
        }

        @TruffleBoundary
        private static SString contents(SPort p) {
            return SString.of(p.contents());
        }
    }

    static final class OpenInputBytevector extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return open(bytevector("open-input-bytevector", args[1]));
        }

        @TruffleBoundary
        private static SPort open(SBytevector bv) {
            // A copy: later changes to the bytevector don't show through the port.
            return SPort.inputBytevector(bv.bytes.clone());
        }
    }

    static final class OpenOutputBytevector extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return SPort.outputBytevector();
        }
    }

    static final class GetOutputBytevector extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            if (!(args[1] instanceof SPort p) || !p.isBytevectorPort()) {
                throw ControlPrimitives.notA("get-output-bytevector", "bytevector output port", args[1]);
            }
            return new SBytevector(p.bytes());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Files (through TruffleFile, design.md D10)

    @TruffleBoundary
    private static TruffleFile file(TruffleLanguage.Env env, String who, SString name) {
        try {
            return env.getPublicTruffleFile(name.toString());
        } catch (SecurityException | UnsupportedOperationException | IllegalArgumentException e) {
            throw fileError(who + ": cannot access file", name);
        }
    }

    static final class OpenFile extends PrimitiveNode {
        static final int TEXT_IN = 0;
        static final int BINARY_IN = 1;
        static final int TEXT_OUT = 2;
        static final int BINARY_OUT = 3;
        private static final String[] NAMES = {"open-input-file", "open-binary-input-file", "open-output-file", "open-binary-output-file"};
        private final int mode;

        OpenFile(int mode) {
            this.mode = mode;
        }

        @Override
        public Object invoke(Object[] args) {
            String who = NAMES[mode];
            return open(SchemeContext.get(this).getEnv(), who, mode, string(who, args[1]));
        }

        @TruffleBoundary
        private static SPort open(TruffleLanguage.Env env, String who, int mode, SString name) {
            TruffleFile file = file(env, who, name);
            try {
                return switch (mode) {
                    case TEXT_IN -> SPort.textInput(file.newBufferedReader(StandardCharsets.UTF_8), false);
                    case BINARY_IN -> SPort.binaryInput(file.newInputStream());
                    case TEXT_OUT -> SPort.textOutput(file.newBufferedWriter(StandardCharsets.UTF_8));
                    default -> SPort.binaryOutput(file.newOutputStream());
                };
            } catch (IOException | SecurityException | UnsupportedOperationException e) {
                throw fileError(who + ": cannot open file", name);
            }
        }
    }

    /** {@code file-exists?}, {@code delete-file}. */
    static final class FileOp extends PrimitiveNode {
        static final int EXISTS = 0;
        static final int DELETE = 1;
        private final int op;

        FileOp(int op) {
            this.op = op;
        }

        @Override
        public Object invoke(Object[] args) {
            String who = op == EXISTS ? "file-exists?" : "delete-file";
            return run(SchemeContext.get(this).getEnv(), op, who, string(who, args[1]));
        }

        @TruffleBoundary
        private static Object run(TruffleLanguage.Env env, int op, String who, SString name) {
            TruffleFile file = file(env, who, name);
            try {
                if (op == EXISTS) {
                    return file.exists();
                }
                file.delete();
                return SConstant.UNSPECIFIED;
            } catch (IOException | SecurityException e) {
                throw fileError(who + ": cannot " + (op == EXISTS ? "access" : "delete") + " file", name);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Input

    /** The reader over a port: {@link Reader} reads code points through this. */
    private static final class PortCharSource implements CharSource {
        private final SPort port;

        PortCharSource(SPort port) {
            this.port = port;
        }

        @Override
        public int peek() {
            return port.peekChar();
        }

        @Override
        public int next() {
            return port.readChar();
        }
    }

    /** {@code (%read port)}: the next datum, read by the reader the port keeps. */
    static final class Read extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            SPort port = textualInput("read", args[1]);
            return read(SchemeContext.get(this), port);
        }

        @TruffleBoundary
        private static Object read(SchemeContext context, SPort port) {
            Reader reader;
            if (port.datumReader instanceof Reader r) {
                reader = r;
            } else {
                reader = new Reader(new PortCharSource(port), context.symbols, false);
                port.datumReader = reader;
            }
            return reader.read();
        }
    }

    /** {@code (%read-char port)}, {@code (%peek-char port)}. */
    static final class ReadChar extends PrimitiveNode {
        private final boolean peek;

        ReadChar(boolean peek) {
            this.peek = peek;
        }

        @Override
        public Object invoke(Object[] args) {
            SPort port = textualInput(peek ? "peek-char" : "read-char", args[1]);
            int c = peek ? port.peekChar() : port.readChar();
            return c < 0 ? SConstant.EOF : SChar.of(c);
        }
    }

    static final class ReadLine extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            return readLine(textualInput("read-line", args[1]));
        }

        /** Up to the next line ending (LF, CR LF or CR), which is consumed but not returned. */
        @TruffleBoundary
        private static Object readLine(SPort port) {
            int c = port.readChar();
            if (c < 0) {
                return SConstant.EOF;
            }
            StringBuilder sb = new StringBuilder();
            while (c >= 0 && c != '\n') {
                if (c == '\r') {
                    if (port.peekChar() == '\n') {
                        port.readChar();
                    }
                    break;
                }
                sb.appendCodePoint(c);
                c = port.readChar();
            }
            return SString.of(sb.toString());
        }
    }

    /** {@code (%read-string k port)}. */
    static final class ReadString extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            int k = index("read-string", args[1], 0, Integer.MAX_VALUE);
            return readString(k, textualInput("read-string", args[2]));
        }

        @TruffleBoundary
        private static Object readString(int k, SPort port) {
            StringBuilder sb = new StringBuilder();
            int n = 0;
            while (n < k) {
                int c = port.readChar();
                if (c < 0) {
                    break;
                }
                sb.appendCodePoint(c);
                n++;
            }
            return n == 0 && k > 0 ? SConstant.EOF : SString.of(sb.toString());
        }
    }

    /** {@code (%char-ready? port)}, {@code (%u8-ready? port)}. */
    static final class Ready extends PrimitiveNode {
        private final boolean binary;

        Ready(boolean binary) {
            this.binary = binary;
        }

        @Override
        public Object invoke(Object[] args) {
            return port(binary ? "u8-ready?" : "char-ready?", args[1], true, binary).ready();
        }
    }

    /** {@code (%read-u8 port)}, {@code (%peek-u8 port)}. */
    static final class ReadU8 extends PrimitiveNode {
        private final boolean peek;

        ReadU8(boolean peek) {
            this.peek = peek;
        }

        @Override
        public Object invoke(Object[] args) {
            SPort port = binaryInput(peek ? "peek-u8" : "read-u8", args[1]);
            int b = peek ? port.peekByte() : port.readByte();
            return b < 0 ? SConstant.EOF : (Object) (long) b;
        }
    }

    /** {@code (%read-bytevector k port)}. */
    static final class ReadBytevector extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            int k = index("read-bytevector", args[1], 0, Integer.MAX_VALUE);
            return read(k, binaryInput("read-bytevector", args[2]));
        }

        @TruffleBoundary
        private static Object read(int k, SPort port) {
            byte[] buffer = new byte[Math.min(k, 8192)];
            int n = 0;
            while (n < k) {
                int b = port.readByte();
                if (b < 0) {
                    break;
                }
                if (n == buffer.length) {
                    buffer = Arrays.copyOf(buffer, (int) Math.min(k, 2L * n));
                }
                buffer[n++] = (byte) b;
            }
            return n == 0 && k > 0 ? SConstant.EOF : new SBytevector(Arrays.copyOf(buffer, n));
        }
    }

    /** {@code (%read-bytevector! bv port [start [end]])}: the number of bytes read, or eof. */
    static final class ReadBytevectorInto extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            SBytevector bv = bytevector("read-bytevector!", args[1]);
            if (bv.immutable) {
                throw ControlPrimitives.notA("read-bytevector!", "mutable bytevector", bv);
            }
            SPort port = binaryInput("read-bytevector!", args[2]);
            int end = args.length > 4 ? index("read-bytevector!", args[4], 0, bv.bytes.length) : bv.bytes.length;
            int start = args.length > 3 ? index("read-bytevector!", args[3], 0, end) : 0;
            return read(bv.bytes, start, end, port);
        }

        @TruffleBoundary
        private static Object read(byte[] bytes, int start, int end, SPort port) {
            int i = start;
            while (i < end) {
                int b = port.readByte();
                if (b < 0) {
                    break;
                }
                bytes[i++] = (byte) b;
            }
            return i == start && end > start ? SConstant.EOF : (Object) (long) (i - start);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Output

    /** {@code (%display obj port)}, {@code %write}, {@code %write-shared}, {@code %write-simple}. */
    static final class Print extends PrimitiveNode {
        private final Printer.Mode mode;
        private final String who;

        Print(Printer.Mode mode, String who) {
            this.mode = mode;
            this.who = who;
        }

        @Override
        public Object invoke(Object[] args) {
            print(textualOutput(who, args[2]), mode, args[1]);
            return SConstant.UNSPECIFIED;
        }

        @TruffleBoundary
        private static void print(SPort port, Printer.Mode mode, Object value) {
            port.write(Printer.print(value, mode));
        }
    }

    /**
     * The REPL's echo (design.md D17, plan.md M7-07): writes each of a top-level form's values the
     * way {@code write} does, one per line, to the context's standard output, and returns them. An
     * unspecified value writes nothing. The expander wraps each top-level form of an interactive
     * source in a call to it, so an input's values appear as each of its forms completes.
     */
    static final class ReplWrite extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            write(SchemeContext.get(this).out(), args[1]);
            return args[1];
        }

        @TruffleBoundary
        private static void write(PrintStream out, Object result) {
            if (result instanceof MultipleValues values) {
                for (Object value : values.values) {
                    out.print(Printer.print(value, Printer.Mode.WRITE) + "\n");
                }
            } else if (result != SConstant.UNSPECIFIED) {
                out.print(Printer.print(result, Printer.Mode.WRITE) + "\n");
            }
            out.flush();
        }
    }

    static final class WriteChar extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            if (!(args[1] instanceof SChar c)) {
                throw ControlPrimitives.notA("write-char", "character", args[1]);
            }
            write(textualOutput("write-char", args[2]), c.codePoint);
            return SConstant.UNSPECIFIED;
        }

        @TruffleBoundary
        private static void write(SPort port, int codePoint) {
            port.write(Character.toString(codePoint));
        }
    }

    /** {@code (%write-string string port [start [end]])}. */
    static final class WriteString extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            SString s = string("write-string", args[1]);
            SPort port = textualOutput("write-string", args[2]);
            int end = args.length > 4 ? index("write-string", args[4], 0, s.length()) : s.length();
            int start = args.length > 3 ? index("write-string", args[3], 0, end) : 0;
            write(port, s, start, end);
            return SConstant.UNSPECIFIED;
        }

        @TruffleBoundary
        private static void write(SPort port, SString s, int start, int end) {
            if (start == 0 && end == s.length()) {
                port.write(s.toString());
                return;
            }
            StringBuilder sb = new StringBuilder(end - start);
            for (int i = start; i < end; i++) {
                sb.appendCodePoint(s.codePointAt(i));
            }
            port.write(sb.toString());
        }
    }

    static final class WriteU8 extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            int b = index("write-u8", args[1], 0, 255);
            binaryOutput("write-u8", args[2]).writeByte(b);
            return SConstant.UNSPECIFIED;
        }
    }

    /** {@code (%write-bytevector bv port [start [end]])}. */
    static final class WriteBytevector extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            SBytevector bv = bytevector("write-bytevector", args[1]);
            SPort port = binaryOutput("write-bytevector", args[2]);
            int end = args.length > 4 ? index("write-bytevector", args[4], 0, bv.bytes.length) : bv.bytes.length;
            int start = args.length > 3 ? index("write-bytevector", args[3], 0, end) : 0;
            port.writeBytes(bv.bytes, start, end);
            return SConstant.UNSPECIFIED;
        }
    }

    static final class Flush extends PrimitiveNode {
        @Override
        public Object invoke(Object[] args) {
            if (!(args[1] instanceof SPort p) || !p.isOutput()) {
                throw ControlPrimitives.notA("flush-output-port", "output port", args[1]);
            }
            if (!p.isOpen()) {
                throw closed("flush-output-port", p);
            }
            p.flush();
            return SConstant.UNSPECIFIED;
        }
    }
}
