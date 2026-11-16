package scheme.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.graalvm.shadowed.org.jline.reader.EndOfFileException;
import org.graalvm.shadowed.org.jline.reader.LineReader;
import org.graalvm.shadowed.org.jline.reader.LineReaderBuilder;
import org.graalvm.shadowed.org.jline.reader.UserInterruptException;
import org.graalvm.shadowed.org.jline.reader.impl.DefaultParser;
import org.graalvm.shadowed.org.jline.terminal.Terminal;
import org.graalvm.shadowed.org.jline.terminal.Terminal.Signal;
import org.graalvm.shadowed.org.jline.terminal.TerminalBuilder;
import org.graalvm.shadowed.org.jline.utils.Signals;

/**
 * The REPL's standard input on a terminal (plan.md M7-07): lines read with JLine (the shadowed
 * JLine 3 that GraalVM's launcher framework ships, design.md D17), so they can be edited and
 * recalled from a history that persists across sessions, as one byte stream. It is the context's
 * standard input, which the REPL reads its lines from through Scheme's console port, like any
 * Scheme code that reads it: so {@code (read-line)} typed at the REPL reads the next line the user
 * types.
 *
 * <p>A line is read only when a reader needs one, so nothing is read ahead. Before it reads its
 * own lines, the REPL sets the prompt ({@code > }, or the continuation prompt inside an
 * unfinished datum); the lines Scheme code reads have no prompt. Only the REPL's inputs go in the
 * history, each as one entry however many lines it has ({@link #addHistory}, plan.md M8-06).
 *
 * <p>Ctrl-C while a line is read ends it ({@link #takeInterrupted}); Ctrl-C at any other time, or
 * while Scheme code reads a line, runs the action {@link #onInterrupt} gives (plan.md M8-06).
 */
final class TerminalInput extends InputStream {

    /** Names the history file instead of {@code ~/.scheme_history}; empty keeps no history file. */
    static final String HISTORY_VARIABLE = "SCHEME_HISTORY";
    private static final String HISTORY_FILE = ".scheme_history";
    private static final byte[] EMPTY = new byte[0];

    private final Terminal terminal;
    private final LineReader reader;
    /** The line being read, with its newline, and how much of it has been. */
    private byte[] line = EMPTY;
    private int position;
    private String prompt = "";
    /** Whether Ctrl-C ended a line since {@link #takeInterrupted}. */
    private boolean interrupted;
    /** What Ctrl-C does during an evaluation, or null. Set and read on the REPL's thread. */
    private Interrupter interrupter;
    private final SchemeReads schemeReads = new SchemeReads();

    private TerminalInput(Terminal terminal, LineReader reader) {
        this.terminal = terminal;
        this.reader = reader;
    }

    /**
     * The system terminal's input, or null if JLine can't open the terminal. The REPL's prompt inside
     * an unfinished datum is {@code continuationPrompt}.
     */
    static TerminalInput open(String continuationPrompt) {
        Terminal terminal;
        try {
            // No native signals: JLine would swap the process's handler each time it reads a line
            // (to its own and back), and between the two, SIGINT would get the JVM's default
            // handler and end the process. The handlers below stay, and pass the signals to the
            // terminal's handler of the moment (plan.md M8-06).
            terminal = TerminalBuilder.builder().system(true).graphemeCluster(false).nativeSignals(false).build();
        } catch (IOException | RuntimeException e) {
            return null;
        }
        // The signals JLine handles while it reads a line: Ctrl-C, a resize, and fg after Ctrl-Z.
        for (Signal signal : new Signal[]{Signal.INT, Signal.WINCH, Signal.CONT}) {
            Signals.register(signal.name(), () -> terminal.raise(signal));
        }
        LineReaderBuilder builder = LineReaderBuilder.builder().terminal(terminal).appName("scheme");
        // The REPL adds its inputs to the history itself, each as one entry (addHistory); a
        // recalled entry of several lines shows its later lines with the continuation prompt.
        builder.variable(LineReader.DISABLE_HISTORY, true);
        builder.variable(LineReader.SECONDARY_PROMPT_PATTERN, continuationPrompt);
        // Text as typed: no shell quoting or escapes (a lone ' or \ doesn't continue the line),
        // and ! doesn't expand history (set! and friends).
        builder.parser(new DefaultParser().quoteChars(new char[0]).escapeChars(new char[0]));
        builder.option(LineReader.Option.DISABLE_EVENT_EXPANSION, true);
        Path history = historyFile();
        if (history != null) {
            builder.variable(LineReader.HISTORY_FILE, history);
        }
        return new TerminalInput(terminal, builder.build());
    }

    /** {@code $SCHEME_HISTORY} if it is set (empty: none), else {@code ~/.scheme_history}. */
    private static Path historyFile() {
        String given = System.getenv(HISTORY_VARIABLE);
        if (given != null) {
            return given.isEmpty() ? null : Paths.get(given);
        }
        String home = System.getProperty("user.home");
        return home == null || home.isEmpty() ? null : Paths.get(home, HISTORY_FILE);
    }

    /** The prompt for the next line read: the REPL's, or "" for Scheme's own reads. */
    void prompt(String text) {
        this.prompt = text;
    }

    /**
     * Ctrl-C during an evaluation goes to {@code target} (plan.md M8-06). JLine handles SIGINT
     * itself while it reads a line, and gives this handler back after: so Ctrl-C comes here, on a
     * thread of its own, while no line is read (the REPL evaluates an input), and as a
     * {@link UserInterruptException} in {@link #fill} while one is. A Ctrl-C that comes here just as
     * Scheme code starts to read a line, before JLine has its handler, interrupts the read too.
     */
    void onInterrupt(Interrupter target) {
        interrupter = target;
        terminal.handle(Signal.INT, (@SuppressWarnings("unused") Signal signal) -> {
            target.request();
            schemeReads.interrupt();
        });
    }

    /** Adds one of the REPL's inputs to the history: one entry, without its last newline. */
    void addHistory(String input) {
        String entry = input.endsWith("\n") ? input.substring(0, input.length() - 1) : input;
        if (!entry.isBlank()) {
            // The history refuses entries while the variable is set, so lines JLine reads stay out.
            reader.setVariable(LineReader.DISABLE_HISTORY, false);
            try {
                reader.getHistory().add(entry);
            } finally {
                reader.setVariable(LineReader.DISABLE_HISTORY, true);
            }
        }
    }

    /** Whether Ctrl-C ended a line since the last call: the REPL then drops its unfinished input. */
    boolean takeInterrupted() {
        boolean result = interrupted;
        interrupted = false;
        return result;
    }

    /** Reads a line if none is left; false at end of input (Ctrl-D on an empty line). */
    private boolean fill() {
        if (position < line.length) {
            return true;
        }
        String text;
        boolean forScheme = prompt.isEmpty();
        Interrupter target = interrupter;
        if (forScheme && target != null && !schemeReads.start(target)) {
            // Ctrl-C came before this read: don't wait for a line, let the evaluation stop.
            line = "\n".getBytes(StandardCharsets.UTF_8);
            position = 0;
            return true;
        }
        try {
            text = reader.readLine(prompt);
        } catch (UserInterruptException e) {
            if (forScheme && target != null) {
                // Scheme code reads: Ctrl-C interrupts the evaluation, which stops when it next
                // polls a safepoint, once this empty line is read.
                target.requestFromEvaluation();
            } else {
                interrupted = true;
            }
            text = "";
        } catch (EndOfFileException e) {
            return false;
        } finally {
            schemeReads.end();
        }
        line = (text + "\n").getBytes(StandardCharsets.UTF_8);
        position = 0;
        return true;
    }

    @Override
    public int read() {
        return fill() ? line[position++] & 0xff : -1;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
        if (length == 0) {
            return 0;
        }
        if (!fill()) {
            return -1;
        }
        int count = Math.min(length, line.length - position);
        System.arraycopy(line, position, buffer, offset, count);
        position += count;
        return count;
    }

    /** What is left of the current line: a reader that has some never waits for the next. */
    @Override
    public int available() {
        return line.length - position;
    }

    /** Saves the history and gives the terminal back. */
    @Override
    public void close() throws IOException {
        try {
            reader.getHistory().save();
        } finally {
            terminal.close();
        }
    }

    /** The read of a line for Scheme code, while one is under way: Ctrl-C interrupts its thread. */
    private static final class SchemeReads {

        private Thread reader;

        /** Starts a read on this thread, unless Ctrl-C has already asked to interrupt the evaluation. */
        synchronized boolean start(Interrupter target) {
            if (target.pending()) {
                return false;
            }
            reader = Thread.currentThread();
            return true;
        }

        /**
         * Ends a read (any read of a line: the REPL's too), and clears the interrupt Ctrl-C may have
         * left on the thread (JLine's handler or ours), or the next read would stop too.
         */
        synchronized void end() {
            reader = null;
            Thread.interrupted();
        }

        /** Ctrl-C: JLine's reads end with a UserInterruptException when their thread is interrupted. */
        synchronized void interrupt() {
            if (reader != null) {
                reader.interrupt();
            }
        }
    }
}
