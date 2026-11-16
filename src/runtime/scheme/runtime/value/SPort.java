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
 */
@ExportLibrary(InteropLibrary.class)
public abstract class SPort implements TruffleObject {

    private boolean open = true;
    /**
     * The datum reader that {@code read} keeps for this textual input port, so that
     * {@code #!fold-case} lasts across reads (R7RS §2.1). Owned by the builtins, which sit above
     * the reader project; null until the first {@code read}.
     */
    public Object datumReader;

    public abstract boolean isInput();

    public final boolean isOutput() {
        return !isInput();
    }

    public abstract boolean isBinary();

    public final boolean isTextual() {
        return !isBinary();
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
    // Operations, each overridden by the kinds of port that support it.

    /** Textual output. */
    public void write(String text) {
        throw unsupported();
    }

    /** Binary output: the low 8 bits of {@code b}. */
    public void writeByte(int b) {
        throw unsupported();
    }

    /** Binary output. */
    public void writeBytes(byte[] bytes, int start, int end) {
        throw unsupported();
    }

    /** Output. */
    public void flush() {
        throw unsupported();
    }

    /** Textual input: the next code point, or -1 at the end. */
    public int readChar() {
        throw unsupported();
    }

    /** Textual input: the next code point without consuming it, or -1 at the end. */
    public int peekChar() {
        throw unsupported();
    }

    /** Binary input: the next byte (0-255), or -1 at the end. */
    public int readByte() {
        throw unsupported();
    }

    /** Binary input: the next byte without consuming it, or -1 at the end. */
    public int peekByte() {
        throw unsupported();
    }

    /** Input: whether the next read won't block ({@code char-ready?}, {@code u8-ready?}). */
    public boolean ready() {
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
    public boolean isStringPort() {
        return this instanceof TextOutput t && t.sink instanceof StringBuilder;
    }

    /** Whether this is a bytevector output port ({@code get-output-bytevector}). */
    public boolean isBytevectorPort() {
        return this instanceof BinaryOutput b && b.out instanceof ByteArrayOutputStream;
    }

    /** A string port's characters so far. */
    @TruffleBoundary
    public String contents() {
        return ((TextOutput) this).sink.toString();
    }

    /** A bytevector port's bytes so far. */
    @TruffleBoundary
    public byte[] bytes() {
        return ((ByteArrayOutputStream) ((BinaryOutput) this).out).toByteArray();
    }

    // ------------------------------------------------------------------------------------------
    // Kinds of port

    private static final class TextOutput extends SPort {
        final Appendable sink;
        private final boolean ownsSink;

        TextOutput(Appendable sink, boolean ownsSink) {
            this.sink = sink;
            this.ownsSink = ownsSink;
        }

        @Override
        public boolean isInput() {
            return false;
        }

        @Override
        public boolean isBinary() {
            return false;
        }

        @Override
        @TruffleBoundary
        public void write(String text) {
            try {
                sink.append(text);
            } catch (IOException e) {
                throw ioError(e);
            }
        }

        @Override
        @TruffleBoundary
        public void flush() {
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
            this.out = out;
        }

        @Override
        public boolean isInput() {
            return false;
        }

        @Override
        public boolean isBinary() {
            return true;
        }

        @Override
        @TruffleBoundary
        public void writeByte(int b) {
            try {
                out.write(b);
            } catch (IOException e) {
                throw ioError(e);
            }
        }

        @Override
        @TruffleBoundary
        public void writeBytes(byte[] bytes, int start, int end) {
            try {
                out.write(bytes, start, end - start);
            } catch (IOException e) {
                throw ioError(e);
            }
        }

        @Override
        @TruffleBoundary
        public void flush() {
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
            this.in = in;
            this.neverBlocks = neverBlocks;
            this.ownsReader = ownsReader;
        }

        @Override
        public boolean isInput() {
            return true;
        }

        @Override
        public boolean isBinary() {
            return false;
        }

        @Override
        @TruffleBoundary
        public int readChar() {
            int c = peekChar();
            peeked = NONE;
            return c;
        }

        @Override
        @TruffleBoundary
        public int peekChar() {
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
        @TruffleBoundary
        public boolean ready() {
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
            this.in = in;
        }

        @Override
        public boolean isInput() {
            return true;
        }

        @Override
        public boolean isBinary() {
            return true;
        }

        @Override
        @TruffleBoundary
        public int readByte() {
            int b = peekByte();
            peeked = NONE;
            return b;
        }

        @Override
        @TruffleBoundary
        public int peekByte() {
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
        public boolean ready() {
            return true;
        }

        @Override
        protected void closeResource() throws IOException {
            in.close();
        }
    }

    // EXPLORATION (design.md D10): a Scheme value to interop, shown as write prints it.

    @ExportMessage
    boolean hasLanguageId() {
        return true;
    }

    @ExportMessage
    String getLanguageId() {
        return SchemeLanguage.ID;
    }

    @ExportMessage
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return InteropValues.display(this);
    }
}
