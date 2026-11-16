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
import org.graalvm.shadowed.org.jline.terminal.TerminalBuilder;

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
 * unfinished datum); the lines Scheme code reads have no prompt, and stay out of the history.
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

    private TerminalInput(Terminal terminal, LineReader reader) {
        this.terminal = terminal;
        this.reader = reader;
    }

    /** The system terminal's input, or null if JLine can't open the terminal. */
    static TerminalInput open() {
        Terminal terminal;
        try {
            terminal = TerminalBuilder.builder().system(true).graphemeCluster(false).build();
        } catch (IOException | RuntimeException e) {
            return null;
        }
        LineReaderBuilder builder = LineReaderBuilder.builder().terminal(terminal).appName("scheme");
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
        // Only the REPL's lines go in the history, not what Scheme code reads.
        reader.setVariable(LineReader.DISABLE_HISTORY, prompt.isEmpty());
        try {
            text = reader.readLine(prompt);
        } catch (UserInterruptException e) {
            // JLine's SIGINT handler interrupted this thread: clear it, or the next read stops too.
            Thread.interrupted();
            interrupted = true;
            text = "";
        } catch (EndOfFileException e) {
            return false;
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
}
