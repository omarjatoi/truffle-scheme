package scheme.runtime.value;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.io.StringReader;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import scheme.runtime.InteropValues;
import scheme.runtime.SchemeError;
import scheme.runtime.SchemeLanguage;

/**
 * A port (R7RS §6.13): textual or binary, input or output. The console ports wrap the context's
 * streams and file ports wrap {@code TruffleFile} streams, so all I/O goes through the language Env
 * (design.md D10); string and bytevector ports live in memory.
 *
 * <p>The builtins check a port's direction, kind and openness before calling the matching methods
 * here; calling, say, {@link #readChar()} on an output port is an internal error. I/O failures are
 * {@link SchemeError}s of kind {@code FILE} (design.md D8).
 *
 * <p>Compiled code reaches a port without knowing its class, so nothing it calls here is virtual
 * (design.md D10, "Ports in compiled code"): a port's direction and kind are {@code final} fields,
 * and each operation is a {@code final} method behind a {@link TruffleBoundary}, since it is I/O,
 * which calls the kind of port's own implementation, a {@code do}-method, as runtime code.
 */
@ExportLibrary(InteropLibrary.class)
public abstract class SPort implements TruffleObject {

    private final boolean input;
    private final boolean binary;
    private boolean open = true;
    /**
     * The datum reader that {@code read} keeps for this textual input port, so that
     * {@code #!fold-case} lasts across reads (R7RS §2.1). Owned by the builtins, which sit above
     * the reader project; null until the first {@code read}.
     */
    public Object datumReader;

    protected SPort(boolean input, boolean binary) {
        this.input = input;
        this.binary = binary;
    }

    public final boolean isInput() {
        return input;
    }

    public final boolean isOutput() {
        return !input;
    }

    public final boolean isBinary() {
        return binary;
    }

    public final boolean isTextual() {
        return !binary;
    }

    public final boolean isOpen() {
        return open;
    }

    /** Closes the port; closing it again does nothing (R7RS §6.13.1). */
    @TruffleBoundary
    public final void close() {
        if (open) {
            open = false;
            try {
                closeResource();
            } catch (IOException e) {
                throw ioError(e);
            }
        }
    }

    /** Releases what the port holds (a file). */
    protected void closeResource() throws IOException {
    }

    @TruffleBoundary
    public static SchemeError ioError(IOException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return new SchemeError(new SErrorObject(SString.of("I/O error: " + message), SConstant.EMPTY_LIST, SErrorObject.Kind.FILE));
    }

    // ------------------------------------------------------------------------------------------
    // Operations: what compiled code calls, each a boundary around the kind of port's own.

    /** Textual output. */
    @TruffleBoundary
    public final void write(String text) {
        doWrite(text);
    }

    /** Binary output: the low 8 bits of {@code b}. */
    @TruffleBoundary
    public final void writeByte(int b) {
        doWriteByte(b);
    }

    /** Binary output. */
    @TruffleBoundary
    public final void writeBytes(byte[] bytes, int start, int end) {
        doWriteBytes(bytes, start, end);
    }

    /** Output. */
    @TruffleBoundary
    public final void flush() {
        doFlush();
    }

    /** Textual input: the next code point, or -1 at the end. */
    @TruffleBoundary
    public final int readChar() {
        return doReadChar();
    }

    /** Textual input: the next code point without consuming it, or -1 at the end. */
    @TruffleBoundary
    public final int peekChar() {
        return doPeekChar();
    }

    /** Binary input: the next byte (0-255), or -1 at the end. */
    @TruffleBoundary
    public final int readByte() {
        return doReadByte();
    }

    /** Binary input: the next byte without consuming it, or -1 at the end. */
    @TruffleBoundary
    public final int peekByte() {
        return doPeekByte();
    }

    /** Input: whether the next read won't block ({@code char-ready?}, {@code u8-ready?}). */
    @TruffleBoundary
    public final boolean ready() {
        return doReady();
    }

    // ------------------------------------------------------------------------------------------
    // The operations of each kind of port, each overridden by the kinds that support it: runtime
    // code, called only from the boundaries above.

    protected void doWrite(String text) {
        throw unsupported();
    }

    protected void doWriteByte(int b) {
        throw unsupported();
    }

    protected void doWriteBytes(byte[] bytes, int start, int end) {
        throw unsupported();
    }

    protected void doFlush() {
        throw unsupported();
    }

    protected int doReadChar() {
        throw unsupported();
    }

    protected int doPeekChar() {
        throw unsupported();
    }

    protected int doReadByte() {
        throw unsupported();
    }

    protected int doPeekByte() {
        throw unsupported();
    }

    protected boolean doReady() {
        throw unsupported();
    }

    private IllegalStateException unsupported() {
        // Internal error, never a Scheme error (design.md D8): the builtins check the port first.
        return new IllegalStateException("operation not supported by " + getClass().getSimpleName());
    }

    // ------------------------------------------------------------------------------------------
    // Constructors

    /** A console output port over one of the context's streams. Closing it leaves the stream open. */
    public static SPort console(PrintStream out) {
        return new TextOutput(out, false);
    }

    public static SPort outputString() {
        return new TextOutput(new StringBuilder(), false);
    }

    /** A textual output port over a file's writer, which closing the port closes. */
    public static SPort textOutput(Appendable sink) {
        return new TextOutput(sink, true);
    }

    public static SPort inputString(String text) {
        return new TextInput(new StringReader(text), true, false);
    }

    /** A textual input port over a decoded stream; {@code console} for the context's input. */
    public static SPort textInput(Reader in, boolean console) {
        return new TextInput(in.markSupported() ? in : new BufferedReader(in), !console, !console);
    }

    public static SPort outputBytevector() {
        return new BinaryOutput(new ByteArrayOutputStream());
    }

    public static SPort binaryOutput(OutputStream out) {
        return new BinaryOutput(out);
    }

    public static SPort inputBytevector(byte[] bytes) {
        return new BinaryInput(new ByteArrayInputStream(bytes));
    }

    public static SPort binaryInput(InputStream in) {
        return new BinaryInput(in);
    }

    /** Whether this is a string output port ({@code get-output-string}). */
    public final boolean isStringPort() {
        return this instanceof TextOutput t && t.sink instanceof StringBuilder;
    }

    /** Whether this is a bytevector output port ({@code get-output-bytevector}). */
    public final boolean isBytevectorPort() {
        return this instanceof BinaryOutput b && b.out instanceof ByteArrayOutputStream;
    }

    /** A string port's characters so far. */
    @TruffleBoundary
    public final String contents() {
        return ((TextOutput) this).sink.toString();
    }

    /** A bytevector port's bytes so far. */
    @TruffleBoundary
    public final byte[] bytes() {
        return ((ByteArrayOutputStream) ((BinaryOutput) this).out).toByteArray();
    }

    // ------------------------------------------------------------------------------------------
    // Kinds of port

    private static final class TextOutput extends SPort {
        final Appendable sink;
        private final boolean ownsSink;

        TextOutput(Appendable sink, boolean ownsSink) {
            super(false, false);
            this.sink = sink;
            this.ownsSink = ownsSink;
        }

        @Override
        protected void doWrite(String text) {
            try {
                sink.append(text);
            } catch (IOException e) {
                throw ioError(e);
            }
        }

        @Override
        protected void doFlush() {
            if (sink instanceof Flushable f) {
                try {
                    f.flush();
                } catch (IOException e) {
                    throw ioError(e);
                }
            }
        }

        @Override
        protected void closeResource() throws IOException {
            if (sink instanceof Flushable f) {
                f.flush();
            }
            if (ownsSink && sink instanceof Closeable c) {
                c.close();
            }
        }
    }

    private static final class BinaryOutput extends SPort {
        final OutputStream out;

        BinaryOutput(OutputStream out) {
            super(false, true);
            this.out = out;
        }

        @Override
        protected void doWriteByte(int b) {
            try {
                out.write(b);
            } catch (IOException e) {
                throw ioError(e);
            }
        }

        @Override
        protected void doWriteBytes(byte[] bytes, int start, int end) {
            try {
                out.write(bytes, start, end - start);
            } catch (IOException e) {
                throw ioError(e);
            }
        }

        @Override
        protected void doFlush() {
            try {
                out.flush();
            } catch (IOException e) {
                throw ioError(e);
            }
        }

        @Override
        protected void closeResource() throws IOException {
            out.close();
        }
    }

    /** Reads code points from a {@link Reader}, combining surrogate pairs, with one of lookahead. */
    private static final class TextInput extends SPort {
        private static final int NONE = -2;

        private final Reader in;
        /** Whether reading never blocks (strings and files): then char-ready? is always true. */
        private final boolean neverBlocks;
        private final boolean ownsReader;
        private int peeked = NONE;

        TextInput(Reader in, boolean neverBlocks, boolean ownsReader) {
            super(true, false);
            this.in = in;
            this.neverBlocks = neverBlocks;
            this.ownsReader = ownsReader;
        }

        @Override
        protected int doReadChar() {
            int c = doPeekChar();
            peeked = NONE;
            return c;
        }

        @Override
        protected int doPeekChar() {
            if (peeked == NONE) {
                peeked = decode();
            }
            return peeked;
        }

        private int decode() {
            try {
                int c = in.read();
                if (c >= 0 && Character.isHighSurrogate((char) c)) {
                    in.mark(1);
                    int low = in.read();
                    if (low >= 0 && Character.isLowSurrogate((char) low)) {
                        return Character.toCodePoint((char) c, (char) low);
                    }
                    in.reset();
                }
                return c;
            } catch (IOException e) {
                throw ioError(e);
            }
        }

        @Override
        protected boolean doReady() {
            if (neverBlocks || peeked != NONE) {
                return true;
            }
            try {
                return in.ready();
            } catch (IOException e) {
                throw ioError(e);
            }
        }

        @Override
        protected void closeResource() throws IOException {
            if (ownsReader) {
                in.close();
            }
        }
    }

    private static final class BinaryInput extends SPort {
        private static final int NONE = -2;

        private final InputStream in;
        private int peeked = NONE;

        BinaryInput(InputStream in) {
            super(true, true);
            this.in = in;
        }

        @Override
        protected int doReadByte() {
            int b = doPeekByte();
            peeked = NONE;
            return b;
        }

        @Override
        protected int doPeekByte() {
            if (peeked == NONE) {
                try {
                    peeked = in.read();
                } catch (IOException e) {
                    throw ioError(e);
                }
            }
            return peeked;
        }

        /** Bytevectors and files never block. */
        @Override
        protected boolean doReady() {
            return true;
        }

        @Override
        protected void closeResource() throws IOException {
            in.close();
        }
    }

    // Interop (design.md D10).

    @ExportMessage
    boolean hasLanguageId() {
        return true;
    }

    @ExportMessage
    String getLanguageId() {
        return SchemeLanguage.ID;
    }

    @ExportMessage
    boolean hasMetaObject() {
        return true;
    }

    @ExportMessage
    Object getMetaObject() {
        return SchemeType.PORT;
    }

    @ExportMessage
    Object toDisplayString(boolean allowSideEffects) {
        return InteropValues.display(this);
    }
}
