package scheme.launcher;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.graalvm.launcher.AbstractLanguageLauncher;
import org.graalvm.options.OptionCategory;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;

/**
 * The {@code scheme} launcher (design.md D17), on GraalVM's launcher framework:
 *
 * <pre>
 * scheme [option...] [-I dir]... [--end-status=n] [file.scm [arg...]]
 * </pre>
 *
 * <p>With a file, it runs the file as a program (design.md D15), and {@code command-line} returns
 * the file and the arguments after it. Each {@code -I dir} adds a directory to the library path.
 * With no file, it starts a REPL in the interaction environment: it reads lines until they hold a
 * complete input (design.md D13's incomplete-source signal), evaluates it, and writes the values of
 * each of its forms. An error prints a message and the REPL goes on; end of input ends it. It reads
 * its lines from Scheme's console input port, so Scheme code it runs reads the same input. On a
 * terminal, the lines come from JLine, with line editing and a history ({@link TerminalInput},
 * plan.md M7-07). The framework adds
 * {@code --help}, {@code --version}, {@code --engine.*}, {@code --log.*} and the other standard
 * options, and the tools' options when they are on the module path.
 *
 * <p>{@code mx r7rs-diff} compares a program's stdout and exit status with chibi-scheme's (design.md
 * D8), so the exit statuses follow chibi-scheme's:
 *
 * <ul>
 * <li>0 when the program or the REPL's input ends, or the status {@code --end-status} gives: so a
 * caller can tell a program that ends from one that calls {@code (exit 0)} (M7-06; the native
 * conformance runner does, as Larceny's exit tests need).</li>
 * <li>The program's status when it calls {@code exit} or {@code emergency-exit}, in a program or in
 * the REPL.</li>
 * <li>{@value #EXIT_UNCAUGHT_ERROR} (EX_SOFTWARE) for an uncaught error in a program. The message
 * goes to stderr; differential tests never compare its wording.</li>
 * <li>{@value #EXIT_NO_INPUT} (EX_NOINPUT) when the file can't be read, {@value #EXIT_USAGE}
 * (EX_USAGE) for a malformed {@code -I}, and the framework's statuses for its own options.</li>
 * </ul>
 */
public final class SchemeMain extends AbstractLanguageLauncher {

    public static final String LANGUAGE = "scheme";
    /** chibi-scheme's status for an uncaught error. */
    public static final int EXIT_UNCAUGHT_ERROR = 70;
    /** EX_USAGE. */
    public static final int EXIT_USAGE = 64;
    /** EX_NOINPUT. */
    public static final int EXIT_NO_INPUT = 66;

    private static final String LIBRARY_PATH_OPTION = "scheme.LibraryPath";
    private static final String END_STATUS_OPTION = "--end-status=";
    /** The prompts, shown only when stdin and stdout are a terminal. */
    private static final String PROMPT = "> ";
    private static final String CONTINUATION_PROMPT = "  ";

    private final List<String> libraryPath = new ArrayList<>();
    /** The program file, or null for the REPL. */
    private String file;
    private String[] programArguments = new String[0];
    /** The status when the program or the REPL's input ends without {@code exit}. */
    private int endStatus;
    /** {@code --version} or {@code --show-version}. */
    private VersionAction versionAction = VersionAction.None;

    public static void main(String[] args) {
        new SchemeMain().launch(args);
    }

    @Override
    protected String getLanguageId() {
        return LANGUAGE;
    }

    /**
     * Takes {@code -I dir} (or {@code -Idir}), then the program file and its arguments. Everything
     * else that starts with {@code -} goes to the framework, which knows the standard options and
     * rejects the rest; {@code --} ends the options.
     */
    @Override
    protected List<String> preprocessArguments(List<String> arguments, Map<String, String> polyglotOptions) {
        List<String> unrecognized = new ArrayList<>();
        int i = 0;
        while (i < arguments.size()) {
            String arg = arguments.get(i);
            if (arg.equals("-I")) {
                if (i + 1 >= arguments.size()) {
                    throw abort("-I needs a directory: -I <dir>", EXIT_USAGE);
                }
                libraryPath.add(arguments.get(i + 1));
                i += 2;
            } else if (arg.startsWith("-I")) {
                libraryPath.add(arg.substring(2));
                i++;
            } else if (arg.startsWith(END_STATUS_OPTION)) {
                endStatus = status(arg.substring(END_STATUS_OPTION.length()));
                i++;
            } else if (arg.equals("--version")) {
                versionAction = VersionAction.PrintAndExit;
                i++;
            } else if (arg.equals("--show-version")) {
                versionAction = VersionAction.PrintAndContinue;
                i++;
            } else if (arg.equals("--")) {
                i++;
                break;
            } else if (arg.startsWith("-") && arg.length() > 1) {
                unrecognized.add(arg);
                i++;
            } else {
                break;
            }
        }
        if (i < arguments.size()) {
            file = arguments.get(i);
            programArguments = arguments.subList(i + 1, arguments.size()).toArray(new String[0]);
        }
        return unrecognized;
    }

    /** An {@code --end-status} value: an exit status, 0 to 255. */
    private int status(String value) {
        try {
            int status = Integer.parseInt(value);
            if (status >= 0 && status <= 255) {
                return status;
            }
        } catch (NumberFormatException e) {
            // Reported below.
        }
        throw abort(END_STATUS_OPTION + " needs an exit status from 0 to 255: " + END_STATUS_OPTION + value, EXIT_USAGE);
    }

    /** {@code --scheme.LibraryPath=...} comes before the {@code -I} directories. */
    @Override
    protected void validateArguments(Map<String, String> polyglotOptions) {
        String given = polyglotOptions.remove(LIBRARY_PATH_OPTION);
        if (given != null && !given.isEmpty()) {
            libraryPath.addAll(0, Arrays.asList(given.split(File.pathSeparator)));
        }
    }

    @Override
    protected void launch(Context.Builder builder) {
        configure(builder, libraryPath);
        if (versionAction != VersionAction.None) {
            try (Engine engine = Engine.create()) {
                runVersionAction(versionAction, engine);
            }
        }
        int status;
        if (file == null) {
            TerminalInput terminal = isTTY() ? TerminalInput.open() : null;
            if (terminal != null) {
                builder.in(terminal);
            }
            try {
                status = repl(builder, terminal, System.out, System.err, endStatus);
            } finally {
                closeQuietly(terminal);
            }
        } else {
            status = runProgram(builder, file, programArguments, System.err, endStatus);
        }
        System.out.flush();
        if (status != 0) {
            throw exit(status);
        }
    }

    private static void closeQuietly(TerminalInput terminal) {
        if (terminal != null) {
            try {
                terminal.close();
            } catch (IOException e) {
                System.err.println("WARNING: cannot save the REPL history: " + e.getMessage());
            }
        }
    }

    @Override
    protected void printHelp(OptionCategory maxCategory) {
        println("Usage: scheme [option...] [-I dir]... [file.scm [arg...]]");
        println("");
        println("Runs file.scm as an R7RS program; (command-line) returns the file and its arguments.");
        println("With no file, starts a REPL that reads from stdin and writes each value.");
        println("On a terminal, its lines can be edited, and its history is kept in ~/.scheme_history");
        println("(or the file $" + TerminalInput.HISTORY_VARIABLE + " names; empty for none).");
        println("");
        println("Scheme options:");
        launcherOption("-I <dir>", "Add a directory to the library path (repeatable).");
        launcherOption("--end-status=<n>", "Exit with status n when the program or the REPL's input ends without exit (default 0).");
        launcherOption("--version", "Print the version and exit.");
        launcherOption("--show-version", "Print the version and continue.");
        launcherOption("--", "End the options: the next argument is the file.");
    }

    @Override
    protected void collectArguments(Set<String> options) {
        options.add("-I");
        options.add(END_STATUS_OPTION);
        options.add("--version");
        options.add("--show-version");
        options.add("--");
    }

    /**
     * A context builder with file access, the process's environment variables and this library path
     * (design.md D7, D10).
     */
    public static Context.Builder newContext(List<String> libraryPath) {
        return configure(Context.newBuilder(LANGUAGE), libraryPath);
    }

    private static Context.Builder configure(Context.Builder builder, List<String> libraryPath) {
        return builder.allowIO(IOAccess.ALL).allowEnvironmentAccess(EnvironmentAccess.INHERIT).option(LIBRARY_PATH_OPTION, String.join(File.pathSeparator, libraryPath));
    }

    /**
     * The launcher without the framework's options, in this process: {@code [-I dir]...
     * [--end-status=n] [file.scm [arg...]]}, with no file reading REPL input from {@code in}.
     * Returns the exit status.
     */
    public static int run(String[] args, InputStream in, OutputStream out, OutputStream err) {
        PrintStream errors = new PrintStream(err, true, StandardCharsets.UTF_8);
        SchemeMain launcher = new SchemeMain();
        try {
            List<String> unrecognized = launcher.preprocessArguments(Arrays.asList(args), new HashMap<>());
            if (!unrecognized.isEmpty()) {
                errors.println("ERROR: unrecognized option: " + unrecognized.get(0));
                return EXIT_USAGE;
            }
        } catch (AbortException e) {
            errors.println("ERROR: " + e.getMessage());
            return e.getExitCode();
        }
        Context.Builder builder = newContext(launcher.libraryPath).in(in).out(out).err(err);
        if (launcher.file == null) {
            return repl(builder, null, out, err, launcher.endStatus);
        }
        return runProgram(builder, launcher.file, launcher.programArguments, errors, launcher.endStatus);
    }

    /** {@link #run(String[], InputStream, OutputStream, OutputStream)} with empty stdin. */
    public static int run(String[] args, OutputStream out, OutputStream err) {
        return run(args, InputStream.nullInputStream(), out, err);
    }

    /**
     * Runs {@code file} as a program, with {@code arguments} after it in {@code command-line}.
     * Returns {@code endStatus} if it ends without exit.
     */
    static int runProgram(Context.Builder builder, String file, String[] arguments, PrintStream errors, int endStatus) {
        Source source;
        try {
            source = Source.newBuilder(LANGUAGE, new File(file)).build();
        } catch (IOException e) {
            errors.println("ERROR: cannot read " + file + ": " + e.getMessage());
            return EXIT_NO_INPUT;
        }
        String[] commandLine = new String[arguments.length + 1];
        commandLine[0] = file;
        System.arraycopy(arguments, 0, commandLine, 1, arguments.length);
        try (Context context = builder.arguments(LANGUAGE, commandLine).build()) {
            context.eval(source);
            return endStatus;
        } catch (PolyglotException e) {
            if (e.isExit()) {
                return e.getExitStatus();
            }
            report(e, errors);
            return EXIT_UNCAUGHT_ERROR;
        }
    }

    /**
     * The REPL: reads lines until they hold a complete input, evaluates it in the interaction
     * environment as an interactive source (which writes the values of each of its forms, design.md
     * D17), and goes on until end of input or {@code exit}. Returns the exit status:
     * {@code endStatus} at end of input.
     *
     * <p>It reads its lines with Scheme's {@code read-line} from the context's console input port,
     * the initial {@code current-input-port}, so that it and the Scheme code it runs share one
     * input and its buffering (plan.md M7-07): {@code (read-line)} reads the line after the one
     * that called it, and the REPL goes on with whatever Scheme code leaves. That port reads the
     * builder's standard input, which on a terminal is {@code terminal}, otherwise null.
     */
    static int repl(Context.Builder builder, TerminalInput terminal, OutputStream out, OutputStream err, int endStatus) {
        PrintStream output = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream errors = new PrintStream(err, true, StandardCharsets.UTF_8);
        try (Context context = builder.arguments(LANGUAGE, new String[0]).build()) {
            // The primitives themselves: a later definition of read-line in the session doesn't
            // change how the REPL reads.
            Value readLine = context.eval(internal("%read-line"));
            Value stdin = context.eval(internal("(%stdin-port)"));
            StringBuilder input = new StringBuilder();
            int first = 1;
            int lineNumber = 0;
            while (true) {
                String line;
                try {
                    if (terminal != null) {
                        terminal.takeInterrupted();
                        terminal.prompt(input.isEmpty() ? PROMPT : CONTINUATION_PROMPT);
                    }
                    Value read = readLine.execute(stdin);
                    line = read.isString() ? read.asString() : null;
                } catch (PolyglotException e) {
                    errors.println("ERROR: cannot read the input: " + e.getMessage());
                    return EXIT_NO_INPUT;
                } finally {
                    if (terminal != null) {
                        terminal.prompt("");
                    }
                }
                if (terminal != null && terminal.takeInterrupted()) {
                    // Ctrl-C drops the unfinished input.
                    input.setLength(0);
                    continue;
                }
                if (line == null) {
                    if (!input.toString().isBlank()) {
                        // End of input inside a datum: report what the reader says about it.
                        Integer status = evaluate(context, input, first, true, errors);
                        if (status != null) {
                            return status;
                        }
                    }
                    if (terminal != null) {
                        output.println();
                    }
                    return endStatus;
                }
                lineNumber++;
                if (input.isEmpty()) {
                    first = lineNumber;
                }
                input.append(line).append('\n');
                if (input.toString().isBlank()) {
                    input.setLength(0);
                    continue;
                }
                Integer status = evaluate(context, input, first, false, errors);
                if (status != null) {
                    return status;
                }
            }
        } catch (PolyglotException e) {
            // Creating the context failed, or closing it after exit or cancellation.
            if (e.isExit()) {
                return e.getExitStatus();
            }
            report(e, errors);
            return EXIT_UNCAUGHT_ERROR;
        }
    }

    /** Launcher code evaluated in the interaction environment, hidden from tools. */
    private static Source internal(String text) {
        return Source.newBuilder(LANGUAGE, text, "<repl>").internal(true).cached(false).buildLiteral();
    }

    /**
     * Evaluates one REPL input, and clears it unless it is incomplete and more may come
     * ({@code atEnd} is false). Returns the exit status if the input exited, otherwise null.
     */
    private static Integer evaluate(Context context, StringBuilder input, int firstLine, boolean atEnd, PrintStream errors) {
        String name = "<repl:" + firstLine + ">";
        Source source = Source.newBuilder(LANGUAGE, input.toString(), name).interactive(true).cached(false).buildLiteral();
        try {
            context.eval(source);
        } catch (PolyglotException e) {
            if (e.isIncompleteSource() && !atEnd) {
                return null;
            }
            input.setLength(0);
            if (e.isExit()) {
                return e.getExitStatus();
            }
            report(e, errors);
            if (e.isCancelled()) {
                return EXIT_UNCAUGHT_ERROR;
            }
            return null;
        }
        input.setLength(0);
        return null;
    }

    private static void report(PolyglotException e, PrintStream errors) {
        if (e.isInternalError()) {
            // Internal errors are bugs and are never turned into Scheme errors (design.md D8).
            errors.println("INTERNAL ERROR: " + e.getMessage());
            e.printStackTrace(errors);
        } else {
            errors.println("ERROR: " + e.getMessage());
        }
    }
}
