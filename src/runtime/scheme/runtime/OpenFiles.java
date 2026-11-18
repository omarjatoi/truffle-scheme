package scheme.runtime;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.Flushable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import scheme.runtime.value.SPort;

/**
 * A context's open file ports, input and output, textual and binary (design.md D10, "Open file
 * ports"). All are buffered; the context writes out what the output ones hold where it writes out
 * standard output ({@link SchemeContext#flushOutput}: when control goes back to the caller, on
 * {@code exit} and when the context closes), and closes them all when it is disposed, so a program
 * that never closes a port loses none of its output, and holds none of its files' descriptors past
 * the context.
 *
 * <p>The ports are held weakly, so tracking them keeps none alive. Each one's buffered stream is
 * held strongly instead: when the program drops a port without closing it, the collector clears
 * the reference and queues it, and the next time the context opens a port or flushes them it
 * writes out (for an output port) and closes that stream, on the context's thread. Closing a port
 * (its stream) stops tracking it. All of it runs on the context's thread (D10: single-threaded),
 * never on a {@code Cleaner}'s.
 */
public final class OpenFiles {

    private final ReferenceQueue<SPort> collected = new ReferenceQueue<>();
    /** The tracked ports: a circular doubly linked list through this sentinel. */
    private final Entry ports = new Entry();
    /** How many ports are tracked: {@link SchemeContext#flushOutput} looks no further when 0. */
    int count;

    OpenFiles() {
    }

    /** A textual output port over a file's stream, encoded in UTF-8 and buffered. */
    @TruffleBoundary
    public SPort textOutput(OutputStream file) {
        TextFile stream = new TextFile(this, file);
        SPort port = SPort.textOutput(stream);
        stream.entry = track(port, stream);
        return port;
    }

    /** A binary output port over a file's stream, buffered. */
    @TruffleBoundary
    public SPort binaryOutput(OutputStream file) {
        BinaryFile stream = new BinaryFile(this, file);
        SPort port = SPort.binaryOutput(stream);
        stream.entry = track(port, stream);
        return port;
    }

    /**
     * A textual input port over a file's stream, which the port buffers and decodes from UTF-8 itself
     * (design.md D10, "Textual input ports decode UTF-8 themselves").
     */
    @TruffleBoundary
    public SPort textInput(InputStream file) {
        TextInputFile stream = new TextInputFile(this, file);
        SPort port = SPort.utf8Input(stream);
        stream.entry = track(port, stream);
        return port;
    }

    /** A binary input port over a file's stream, buffered. */
    @TruffleBoundary
    public SPort binaryInput(InputStream file) {
        BinaryInputFile stream = new BinaryInputFile(this, file);
        SPort port = SPort.binaryInput(stream);
        stream.entry = track(port, stream);
        return port;
    }

    /** How many ports are tracked, including collected ones not yet closed (for tests). */
    public int size() {
        return count;
    }

    private Entry track(SPort port, FileStream stream) {
        reap();
        Entry entry = new Entry(port, stream, collected);
        entry.prev = ports.prev;
        entry.next = ports;
        ports.prev.next = entry;
        ports.prev = entry;
        count++;
        return entry;
    }

    private void untrack(Entry entry) {
        if (entry.next != null) {
            entry.prev.next = entry.next;
            entry.next.prev = entry.prev;
            entry.prev = null;
            entry.next = null;
            count--;
        }
    }

    /**
     * Writes out what every open file port holds, after writing out and closing the ports that were
     * collected. An I/O error is ignored here, as C's {@code exit} ignores one; a program that needs
     * to know calls {@code flush-output-port} or {@code close-port}, which raise it.
     */
    @TruffleBoundary
    void flush() {
        reap();
        for (Entry e = ports.next; e != ports; e = e.next) {
            try {
                e.stream.flush();
            } catch (IOException ignored) {
                // See above.
            }
        }
    }

    /** Closes every open file port's stream (the context is being disposed). */
    @TruffleBoundary
    void close() {
        reap();
        while (ports.next != ports) {
            Entry e = ports.next;
            try {
                e.stream.close();
            } catch (IOException ignored) {
                // As in flush. Closing untracked it first.
            }
        }
    }

    /**
     * Writes out (output ports) and closes the streams of the ports that were collected without
     * being closed.
     */
    private void reap() {
        for (Entry e = (Entry) collected.poll(); e != null; e = (Entry) collected.poll()) {
            if (e.next != null) {
                try {
                    e.stream.close();
                } catch (IOException ignored) {
                    // As in flush. Closing untracked it first.
                }
            }
        }
    }

    /**
     * A port's buffered stream, which stops tracking the port when it is closed. An input stream's
     * {@code flush} does nothing.
     */
    private interface FileStream extends Closeable, Flushable {
    }

    /** A tracked port: weakly, and its stream strongly. */
    private static final class Entry extends WeakReference<SPort> {
        final FileStream stream;
        Entry prev;
        Entry next;

        /** The list's sentinel. */
        Entry() {
            super(null);
            this.stream = null;
            this.prev = this;
            this.next = this;
        }

        Entry(SPort port, FileStream stream, ReferenceQueue<SPort> queue) {
            super(port, queue);
            this.stream = stream;
        }
    }

    /** As {@code TruffleFile.newBufferedWriter(UTF_8)}, which closing stops tracking. */
    private static final class TextFile extends BufferedWriter implements FileStream {
        private final OpenFiles files;
        Entry entry;

        TextFile(OpenFiles files, OutputStream file) {
            super(new OutputStreamWriter(file, StandardCharsets.UTF_8));
            this.files = files;
        }

        @Override
        public void close() throws IOException {
            if (entry != null) {
                files.untrack(entry);
                entry = null;
            }
            super.close();
        }
    }

    private static final class BinaryFile extends BufferedOutputStream implements FileStream {
        private final OpenFiles files;
        Entry entry;

        BinaryFile(OpenFiles files, OutputStream file) {
            super(file);
            this.files = files;
        }

        @Override
        public void close() throws IOException {
            if (entry != null) {
                files.untrack(entry);
                entry = null;
            }
            super.close();
        }
    }

    /**
     * A file's stream, which the port reads through a buffer of its own as it decodes UTF-8
     * (design.md D10), and which closing stops tracking.
     */
    private static final class TextInputFile extends FilterInputStream implements FileStream {
        private final OpenFiles files;
        Entry entry;

        TextInputFile(OpenFiles files, InputStream file) {
            super(file);
            this.files = files;
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() throws IOException {
            if (entry != null) {
                files.untrack(entry);
                entry = null;
            }
            super.close();
        }
    }

    private static final class BinaryInputFile extends BufferedInputStream implements FileStream {
        private final OpenFiles files;
        Entry entry;

        BinaryInputFile(OpenFiles files, InputStream file) {
            super(file);
            this.files = files;
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() throws IOException {
            if (entry != null) {
                files.untrack(entry);
                entry = null;
            }
            super.close();
        }
    }
}
