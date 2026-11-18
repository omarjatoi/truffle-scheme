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
import java.util.Arrays;

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

    /**
     * Textual input ({@code read-string}): a new string of the next {@code k} characters (code
     * points), or of as many as are left before the end; the eof object if {@code k > 0} and the
     * port is at the end. Copies from the port's reader in bulk, into the string's array.
     */
    @TruffleBoundary
    public final Object readString(int k) {
        return doReadString(k);
    }

    /**
     * Textual input ({@code read-line}): the characters up to the next line ending, LF, CR LF or a
     * CR alone, which is read but not returned, or up to the end if no line ending comes first; the
     * eof object if the port is at the end. Finds the line ending in the port's reader's buffer.
     */
    @TruffleBoundary
    public final Object readLine() {
        return doReadLine();
    }

    /**
     * Binary input ({@code read-bytevector}): the next {@code k} bytes, or as many as are left
     * before the end; null if {@code k > 0} and the port is at the end. Copies from the port's
     * stream in bulk.
     */
    @TruffleBoundary
    public final byte[] readBytes(int k) {
        return doReadBytes(k);
    }

    /**
     * Binary input ({@code read-bytevector!}): the next {@code len} bytes, or as many as are left
     * before the end, into {@code bytes} from {@code off}; the number read, or -1 if {@code len > 0}
     * and the port is at the end. Copies from the port's stream in bulk.
     */
    @TruffleBoundary
    public final int readBytes(byte[] bytes, int off, int len) {
        return doReadBytes(bytes, off, len);
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

    protected Object doReadString(int k) {
        throw unsupported();
    }

    protected Object doReadLine() {
        throw unsupported();
    }

    protected byte[] doReadBytes(int k) {
        throw unsupported();
    }

    protected int doReadBytes(byte[] bytes, int off, int len) {
        throw unsupported();
    }

    protected boolean doReady() {
        throw unsupported();
    }

    /** The most a bulk read asks of a reader or stream at once, and the buffer it starts with. */
    private static final int CHUNK = 8192;

    /**
     * The first chunk {@code read-line} asks of a reader that isn't buffered, doubled for each
     * chunk of a longer line up to {@link #CHUNK}.
     */
    private static final int LINE = 128;

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
        return new TextInput(new StringReader(text), false);
    }

    /**
     * A textual input port over a reader, which closing the port closes. (A file's port and the
     * console's decode their bytes themselves: {@link #utf8Input}, {@link #consoleInput}.)
     */
    public static SPort textInput(Reader in) {
        return new TextInput(in.markSupported() ? in : new BufferedReader(in), true);
    }

    /**
     * A textual input port over a file's bytes, which it decodes from UTF-8 itself (design.md D10,
     * "Textual input ports decode UTF-8 themselves"). Closing the port closes the stream.
     */
    public static SPort utf8Input(InputStream in) {
        return new Utf8Input(in, false);
    }

    /**
     * The console input port over the context's standard input, whose bytes it decodes from UTF-8
     * itself, as a file's port does, but reading only what each read needs, so that it never waits
     * for more input than that (design.md D10, "Textual input ports decode UTF-8 themselves").
     * Closing the port leaves the stream open.
     */
    public static SPort consoleInput(InputStream in) {
        return new Utf8Input(in, true);
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

    /**
     * Reads code points from a {@link Reader} (a string port's), combining surrogate pairs, with one
     * of lookahead. Its reader never blocks, so char-ready? is always true.
     */
    private static final class TextInput extends SPort {
        private static final int NONE = -2;

        private final Reader in;
        private final boolean ownsReader;
        private int peeked = NONE;

        TextInput(Reader in, boolean ownsReader) {
            super(true, false);
            this.in = in;
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

        /**
         * Asks the reader for at most as many UTF-16 units as characters may still be wanted, so
         * never reads too many (a character is one unit or two). A high surrogate that ends a read
         * is paired with the low one after it, as {@link #decode} pairs them, so a pair is never
         * split. Reads until {@code k} characters are read or the reader ends, as
         * {@link #doReadChar} would. The reads go into one array, which
         * {@link SString#of(char[], int, int)} makes the string in one pass (Latin-1 bytes, or code
         * points from the first unit above 255), with no {@code String} between.
         *
         * <p>The units read are counted as characters only when that could make {@code k}: until
         * then each is taken for a character, which never asks for too many, so reading a whole
         * file counts nothing.
         */
        @Override
        protected Object doReadString(int k) {
            if (k == 0) {
                return SString.of("");
            }
            // Room for a peeked pair, and for the low surrogate after a read.
            char[] text = new char[Math.min(k, CHUNK) + 1];
            int units = 0;
            // The characters in text[0, counted).
            int n = 0;
            int counted = 0;
            if (peeked != NONE) {
                int c = peeked;
                peeked = NONE;
                if (c < 0) {
                    return SConstant.EOF;
                }
                units = Character.toChars(c, text, 0);
            }
            try {
                while (true) {
                    // At most n + (units - counted) characters are read.
                    int left = k - n - (units - counted);
                    if (left <= 0) {
                        if (counted == units) {
                            break;
                        }
                        n += Character.codePointCount(text, counted, units - counted);
                        counted = units;
                        continue;
                    }
                    int want = Math.min(left, CHUNK);
                    if (text.length - units <= want) {
                        text = Arrays.copyOf(text, Math.max(2 * text.length, units + want + 1));
                    }
                    int r = in.read(text, units, want);
                    if (r < 0) {
                        break;
                    }
                    units += r;
                    if (r > 0 && Character.isHighSurrogate(text[units - 1])) {
                        in.mark(1);
                        int low = in.read();
                        if (low >= 0 && Character.isLowSurrogate((char) low)) {
                            text[units++] = (char) low;
                        } else {
                            in.reset();
                        }
                    }
                }
            } catch (IOException e) {
                throw ioError(e);
            }
            return units == 0 ? SConstant.EOF : SString.of(text, 0, units);
        }

        /**
         * A {@link BufferedReader} (one that {@link #textInput} wraps a reader in) finds the line
         * in its buffer itself: {@link BufferedReader#readLine} ends a line exactly where a
         * character at a time did, at LF, CR LF or a CR alone. After a CR it skips an LF on its
         * next read of any kind, where a character at a time read the LF at once: the same
         * characters. Another reader (a string port's) is read in chunks that {@link #scanLine}
         * searches.
         */
        @Override
        protected Object doReadLine() {
            StringBuilder start = null;
            try {
                if (peeked != NONE) {
                    int c = peeked;
                    peeked = NONE;
                    if (c < 0) {
                        return SConstant.EOF;
                    } else if (c == '\n' || c == '\r') {
                        if (c == '\r') {
                            skipLineFeed();
                        }
                        return SString.of("");
                    }
                    start = new StringBuilder().appendCodePoint(c);
                }
                if (in instanceof BufferedReader buffered) {
                    String line = buffered.readLine();
                    if (start == null) {
                        return line == null ? SConstant.EOF : SString.of(line);
                    }
                    return SString.of(line == null ? start.toString() : start.append(line).toString());
                }
                return scanLine(start);
            } catch (IOException e) {
                throw ioError(e);
            }
        }

        /** Reads the LF after a CR that ended a line, if there is one. */
        private void skipLineFeed() throws IOException {
            in.mark(1);
            if (in.read() != '\n') {
                in.reset();
            }
        }

        /**
         * Reads the reader in chunks, from {@link #LINE} units up to {@link #CHUNK}, marking where
         * each starts: in the chunk with the line ending, goes back to the mark and skips the line
         * and its ending. The text goes into one array, {@link SString}'s without a copy between.
         */
        private Object scanLine(StringBuilder start) throws IOException {
            char[] line = new char[start == null ? LINE : Math.max(LINE, 2 * start.length())];
            int n = 0;
            if (start != null) {
                n = start.length();
                start.getChars(0, n, line, 0);
            }
            int want = LINE;
            while (true) {
                if (line.length - n < want) {
                    line = Arrays.copyOf(line, Math.max(2 * line.length, n + want));
                }
                in.mark(want);
                int r = in.read(line, n, want);
                if (r < 0) {
                    return n == 0 && start == null ? SConstant.EOF : SString.of(line, 0, n);
                }
                for (int i = n; i < n + r; i++) {
                    char c = line[i];
                    if (c == '\n' || c == '\r') {
                        int skip = i + 1 - n;
                        boolean lineFeed = c == '\r' && i + 1 < n + r && line[i + 1] == '\n';
                        in.reset();
                        skipFully(lineFeed ? skip + 1 : skip);
                        if (c == '\r' && i + 1 == n + r) {
                            skipLineFeed();
                        }
                        return SString.of(line, 0, i);
                    }
                }
                n += r;
                want = Math.min(2 * want, CHUNK);
            }
        }

        private void skipFully(long count) throws IOException {
            long left = count;
            while (left > 0) {
                long skipped = in.skip(left);
                if (skipped <= 0) {
                    throw new IOException("reader skipped " + skipped + " of " + left + " characters it had read");
                }
                left -= skipped;
            }
        }

        @Override
        protected boolean doReady() {
            return true;
        }

        @Override
        protected void closeResource() throws IOException {
            if (ownsReader) {
                in.close();
            }
        }
    }

    /**
     * A file's text or standard input's, decoded from UTF-8 by the port itself (design.md D10,
     * "Textual input ports decode UTF-8 themselves"): the stream's bytes go into the port's buffer,
     * and from there straight into a string's Latin-1 bytes or code points (D1), with no reader or
     * {@code char[]} between. Malformed input is replaced as the JDK's UTF-8 decoder replaces it,
     * which is how the port read it until it decoded it itself: one U+FFFD for each malformed
     * sequence, of the length the decoder gives it ({@link #decode}). A sequence that the buffer's
     * end cuts is completed from the next read of the stream; one that the stream's end cuts is
     * malformed.
     *
     * <p>The port reads the stream only when its buffer is empty, and again only for the rest of a
     * sequence that the buffer's end cuts: a read of a pipe or a terminal returns what has come, so
     * the port never waits for more than the character, line or string it reads (the console's
     * port; a file's is read the same way). After a CR that ends a line at the buffer's end, it
     * skips an LF on the stream's next read, not at once, which would wait for the character after
     * a line typed at a terminal.
     */
    private static final class Utf8Input extends SPort {
        private static final int NONE = -2;
        /** What {@link #decode} returns for a sequence the buffer's end cuts. */
        private static final int MORE = -1;

        private final InputStream in;
        /** Whether this is the console's port: a read may block, and closing leaves the stream open. */
        private final boolean console;
        private final byte[] buffer = new byte[CHUNK];
        /** The bytes not decoded yet: {@code buffer[pos, limit)}. */
        private int pos;
        private int limit;
        /**
         * Whether the stream ended at its last read: nothing comes after {@code limit} (though a
         * terminal may give more on a later read).
         */
        private boolean end;
        /**
         * Whether a CR ended a line at the buffer's end, so that the stream's next read skips an LF
         * that it starts with. Only while the buffer is empty.
         */
        private boolean lineFeedPending;
        private int peeked = NONE;

        Utf8Input(InputStream in, boolean console) {
            super(true, false);
            this.in = in;
            this.console = console;
        }

        /**
         * Reads the stream until the buffer holds at least {@code n} bytes (at most 4) not decoded
         * yet, or the stream ends; first moves those bytes to the buffer's start if they are fewer.
         * Each read returns what the stream has, at least a byte: a pipe's, what has come. Returns
         * how many bytes the buffer holds.
         */
        private int fill(int n) {
            int left = limit - pos;
            if (left >= n) {
                return left;
            }
            compact();
            while (limit - pos < n) {
                if (read(buffer.length - limit) < 0) {
                    break;
                }
            }
            return limit - pos;
        }

        /** Moves the bytes not decoded yet to the buffer's start. */
        private void compact() {
            if (pos > 0) {
                System.arraycopy(buffer, pos, buffer, 0, limit - pos);
                limit -= pos;
                pos = 0;
            }
        }

        /**
         * One read of the stream, of at most {@code most} bytes, into the buffer after
         * {@code limit}, which skips an LF after a CR that ended a line ({@link #lineFeedPending}).
         * Returns what the stream's read returned.
         */
        private int read(int most) {
            int r;
            try {
                r = in.read(buffer, limit, most);
            } catch (IOException e) {
                throw ioError(e);
            }
            if (r < 0) {
                end = true;
            } else if (r > 0) {
                end = false;
                limit += r;
                if (lineFeedPending) {
                    lineFeedPending = false;
                    if (buffer[pos] == '\n') {
                        pos++;
                    }
                }
            }
            return r;
        }

        /**
         * The character at {@code bytes[p]}, given {@code bytes[p, l)} and, if {@code end}, nothing
         * after them: {@code (length << 24) | codePoint}, where {@code length} is the number of
         * bytes it takes, or {@link #MORE} if a sequence the end of {@code l} cuts may still be
         * completed. A malformed sequence is U+FFFD, as long as the JDK's UTF-8 decoder
         * ({@code sun.nio.cs.UTF_8.Decoder}, which {@code InputStreamReader} replaces malformed
         * input with) makes it: a byte that can't start a sequence (80-C1, F5-FF) is one; in a
         * sequence of 2 to 4 bytes, the bytes before one that can't come where it is (not a
         * continuation byte, or out of range as the second: E0 80-9F, F0 80-8F, F4 90-BF) are one;
         * an encoded surrogate (ED A0-BF xx) is one of 3 bytes; and a sequence that the end cuts is
         * one of the bytes left.
         */
        private static int decode(byte[] bytes, int p, int l, boolean end) {
            int b1 = bytes[p];
            if (b1 >= 0) {
                return (1 << 24) | b1;
            }
            int left = l - p;
            if ((b1 & 0xe0) == 0xc0 && (b1 & 0x1e) != 0) {
                // C2-DF: 2 bytes.
                if (left < 2) {
                    return end ? malformed(left) : MORE;
                }
                int b2 = bytes[p + 1];
                if ((b2 & 0xc0) != 0x80) {
                    return malformed(1);
                }
                return (2 << 24) | ((b1 & 0x1f) << 6) | (b2 & 0x3f);
            } else if ((b1 & 0xf0) == 0xe0) {
                // E0-EF: 3 bytes.
                if (left < 2) {
                    return end ? malformed(left) : MORE;
                }
                int b2 = bytes[p + 1];
                if ((b1 == (byte) 0xe0 && (b2 & 0xe0) == 0x80) || (b2 & 0xc0) != 0x80) {
                    return malformed(1);
                }
                if (left < 3) {
                    return end ? malformed(left) : MORE;
                }
                int b3 = bytes[p + 2];
                if ((b3 & 0xc0) != 0x80) {
                    return malformed(2);
                }
                int c = ((b1 & 0x0f) << 12) | ((b2 & 0x3f) << 6) | (b3 & 0x3f);
                return Character.isSurrogate((char) c) ? malformed(3) : (3 << 24) | c;
            } else if ((b1 & 0xf8) == 0xf0) {
                // F0-F7: 4 bytes, of which F5-F7 start none.
                int u1 = b1 & 0xff;
                if (u1 > 0xf4) {
                    return malformed(1);
                }
                if (left < 2) {
                    return end ? malformed(left) : MORE;
                }
                int b2 = bytes[p + 1] & 0xff;
                if ((u1 == 0xf0 && (b2 < 0x90 || b2 > 0xbf)) || (u1 == 0xf4 && (b2 & 0xf0) != 0x80) || (b2 & 0xc0) != 0x80) {
                    return malformed(1);
                }
                if (left < 3) {
                    return end ? malformed(left) : MORE;
                }
                int b3 = bytes[p + 2];
                if ((b3 & 0xc0) != 0x80) {
                    return malformed(2);
                }
                if (left < 4) {
                    return end ? malformed(left) : MORE;
                }
                int b4 = bytes[p + 3];
                if ((b4 & 0xc0) != 0x80) {
                    return malformed(3);
                }
                return (4 << 24) | ((u1 & 0x07) << 18) | ((b2 & 0x3f) << 12) | ((b3 & 0x3f) << 6) | (b4 & 0x3f);
            }
            return malformed(1);
        }

        private static int malformed(int length) {
            return (length << 24) | 0xfffd;
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
                peeked = next();
            }
            return peeked;
        }

        /**
         * Decodes the next character, or returns -1 at the end. Reads the stream only if the buffer
         * is empty, and again only while a sequence is cut.
         */
        private int next() {
            if (pos < limit) {
                int b = buffer[pos];
                if (b >= 0) {
                    pos++;
                    return b;
                }
            } else if (fill(1) == 0) {
                return -1;
            }
            while (true) {
                int r = decode(buffer, pos, limit, end);
                if (r != MORE) {
                    pos += r >>> 24;
                    return r & 0x1fffff;
                }
                // At the stream's end, decode makes what the end cuts malformed.
                fill(limit - pos + 1);
            }
        }

        @Override
        protected Object doReadString(int k) {
            if (k == 0) {
                return SString.of("");
            }
            return decodeString(k, false);
        }

        @Override
        protected Object doReadLine() {
            if (peeked == '\n' || peeked == '\r') {
                if (peeked == '\r') {
                    skipLineFeed();
                }
                peeked = NONE;
                return SString.of("");
            }
            if (peeked == NONE) {
                // An ASCII line that ends in the buffer: one scan, and a copy of its bytes.
                byte[] bytes = buffer;
                int i = pos;
                int b = 0;
                while (i < limit && ((b = bytes[i]) > '\r' || (b >= 0 && b != '\n' && b != '\r'))) {
                    i++;
                }
                if (i < limit && b >= 0) {
                    byte[] line = Arrays.copyOfRange(bytes, pos, i);
                    pos = i + 1;
                    if (b == '\r') {
                        skipLineFeed();
                    }
                    return SString.ofLatin1(line, line.length);
                }
            }
            return decodeString(Integer.MAX_VALUE, true);
        }

        /**
         * Skips the LF after a CR that ended a line, if there is one: at once if the buffer holds
         * the next byte, otherwise on the stream's next read, so that a CR typed at a terminal ends
         * a line without waiting for the character after it.
         */
        private void skipLineFeed() {
            if (pos < limit) {
                if (buffer[pos] == '\n') {
                    pos++;
                }
            } else {
                lineFeedPending = true;
            }
        }

        /**
         * Decodes up to {@code k} characters, a peeked one first, into a new string, or for
         * {@code read-line} ({@code line}) up to a line ending, LF, CR LF or a CR alone, which it
         * reads but leaves out; the eof object if the port is at the end. The characters go into
         * Latin-1 bytes until one above 255 comes, then into code points (D1), each run of ASCII
         * bytes copied by a loop of its own. The array has room for every character the buffer's
         * bytes up to a line ending can make (at most one a byte), so it grows only between buffers:
         * a line in the buffer gets an array of its length, and a {@code read-string} of more than a
         * buffer starts with room for what the stream has left, so a whole file's string is never
         * copied to grow. LF and CR are never part of a longer sequence, so a line ends at the first
         * one.
         */
        private Object decodeString(int k, boolean line) {
            int first = peeked;
            peeked = NONE;
            if (first == -1) {
                return SConstant.EOF;
            }
            int n = first >= 0 ? 1 : 0;
            int room;
            if (line) {
                room = n;
            } else if (k <= CHUNK) {
                room = k;
            } else {
                room = (int) Math.min(k, Math.max(CHUNK, (long) limit - pos + available() + n));
            }
            byte[] latin1 = null;
            int[] wide = null;
            if (first > 0xff) {
                wide = new int[room];
                wide[0] = first;
            } else {
                latin1 = new byte[room];
                if (n > 0) {
                    latin1[0] = (byte) first;
                }
            }
            boolean ended = false;
            // Whether a sequence that the buffer's end cuts stopped the last pass: this one reads
            // the rest of it. Otherwise the stream is read only when the buffer is empty.
            boolean cut = false;
            while (n < k && !ended) {
                int need = cut ? limit - pos + 1 : 1;
                if (limit - pos < need && fill(need) == 0) {
                    break;
                }
                byte[] bytes = buffer;
                int p = pos;
                int l = limit;
                int stop = line ? lineEnding(bytes, p, l) : l;
                int most = Math.min(k - n, stop - p);
                int capacity = latin1 != null ? latin1.length : wide.length;
                if (capacity - n < most) {
                    // A line that ends in the buffer gets exactly its room.
                    long grown = line && stop < l ? n + most : Math.min(k, Math.max(2L * capacity, (long) n + most));
                    if (latin1 != null) {
                        latin1 = Arrays.copyOf(latin1, (int) grown);
                    } else {
                        wide = Arrays.copyOf(wide, (int) grown);
                    }
                }
                if (latin1 != null) {
                    byte[] out = latin1;
                    while (p < stop && n < k) {
                        int run = ascii(bytes, p, Math.min(stop - p, k - n));
                        System.arraycopy(bytes, p, out, n, run);
                        p += run;
                        n += run;
                        if (p == stop || n == k) {
                            break;
                        }
                        // Never MORE before a line ending, which ends any sequence.
                        int r = decode(bytes, p, l, end);
                        if (r == MORE) {
                            break;
                        }
                        p += r >>> 24;
                        int c = r & 0x1fffff;
                        if (c > 0xff) {
                            wide = new int[out.length];
                            for (int i = 0; i < n; i++) {
                                wide[i] = out[i] & 0xff;
                            }
                            wide[n++] = c;
                            latin1 = null;
                            break;
                        }
                        out[n++] = (byte) c;
                    }
                }
                if (wide != null) {
                    int[] out = wide;
                    while (p < stop && n < k) {
                        int run = ascii(bytes, p, Math.min(stop - p, k - n));
                        int shift = n - p;
                        for (int j = p; j < p + run; j++) {
                            out[shift + j] = bytes[j];
                        }
                        p += run;
                        n += run;
                        if (p == stop || n == k) {
                            break;
                        }
                        int r = decode(bytes, p, l, end);
                        if (r == MORE) {
                            break;
                        }
                        p += r >>> 24;
                        out[n++] = r & 0x1fffff;
                    }
                }
                if (p == stop && stop < l) {
                    ended = true;
                    p++;
                    pos = p;
                    if (bytes[stop] == '\r') {
                        skipLineFeed();
                    }
                } else {
                    // A pass stops at the stop, at k characters or at a sequence the buffer cuts.
                    cut = p < stop && n < k;
                    pos = p;
                }
            }
            if (n == 0 && !ended) {
                return SConstant.EOF;
            }
            return latin1 != null ? SString.ofLatin1(latin1, n) : SString.ofCodePoints(wide, n);
        }

        /** Where the first LF or CR in {@code bytes[p, l)} is, or {@code l}. */
        private static int lineEnding(byte[] bytes, int p, int l) {
            int i = p;
            int b;
            while (i < l && (b = bytes[i]) != '\n' && b != '\r') {
                i++;
            }
            return i;
        }

        /** How many of the {@code most} bytes from {@code bytes[p]} are ASCII. */
        private static int ascii(byte[] bytes, int p, int most) {
            int i = 0;
            while (i < most && bytes[p + i] >= 0) {
                i++;
            }
            return i;
        }

        /** How many bytes the stream can give without blocking: for a file, what is left of it. */
        private int available() {
            try {
                return in.available();
            } catch (IOException e) {
                throw ioError(e);
            }
        }

        /**
         * Files never block. On the console, a character is ready if the buffer holds one (a
         * malformed sequence's U+FFFD included), or once the bytes that the stream has without
         * blocking ({@code available()}) make one, which this reads into the buffer.
         */
        @Override
        protected boolean doReady() {
            if (!console || peeked != NONE) {
                return true;
            }
            while (true) {
                if (pos < limit && decode(buffer, pos, limit, end) != MORE) {
                    return true;
                }
                int available = available();
                if (available <= 0) {
                    return false;
                }
                compact();
                if (read(Math.min(available, buffer.length - limit)) < 0) {
                    // The end after all: read-char returns at once.
                    return true;
                }
            }
        }

        @Override
        protected void closeResource() throws IOException {
            if (!console) {
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

        @Override
        protected byte[] doReadBytes(int k) {
            byte[] buffer = new byte[Math.min(k, CHUNK)];
            int n = 0;
            while (n < k) {
                if (n == buffer.length) {
                    buffer = Arrays.copyOf(buffer, (int) Math.min(k, 2L * n));
                }
                int r = doReadBytes(buffer, n, buffer.length - n);
                if (r < 0) {
                    break;
                }
                n += r;
                if (n < buffer.length) {
                    break; // A read stops short only at the end.
                }
            }
            if (n == 0 && k > 0) {
                return null;
            }
            return n == buffer.length ? buffer : Arrays.copyOf(buffer, n);
        }

        /** Reads until {@code len} bytes are read or the stream ends, as {@link #doReadByte} would. */
        @Override
        protected int doReadBytes(byte[] bytes, int off, int len) {
            if (len == 0) {
                return 0;
            }
            int n = 0;
            if (peeked != NONE) {
                int b = peeked;
                peeked = NONE;
                if (b < 0) {
                    return -1;
                }
                bytes[off] = (byte) b;
                n = 1;
            }
            try {
                n += in.readNBytes(bytes, off + n, len - n);
            } catch (IOException e) {
                throw ioError(e);
            }
            return n == 0 ? -1 : n;
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
