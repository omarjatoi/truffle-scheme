package scheme.launcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;

/**
 * Runs conformance sections (tests/r7rs/*.scm, tests/r7rs-larceny/tests/scheme/run/*.sps) in one
 * JVM, each in a fresh context, for {@code mx r7rs-test} and {@code mx r7rs-status}:
 *
 * <pre>
 * ConformanceRunner [-I dir]... [-C dir] ([--arg arg]... [--env name=value]... file)...
 * </pre>
 *
 * <p>{@code -I} adds a directory to the library path and {@code -C} sets the contexts' working
 * directory. {@code --arg} and {@code --env} apply to the next file only: {@code command-line}
 * returns the file and its arguments (as {@link SchemeMain}'s does), and each {@code --env} adds
 * an environment variable.
 *
 * <p>For each file it prints the section's own output (the {@code @@r7rs-test} lines from the test
 * shims in tests/lib) followed by one summary line, in the order of the files:
 * {@code @@section <file> <ok | exit <status> | error: ... | timeout ...>}. The mx side counts the
 * result lines.
 *
 * <p>This is the embedder, not language code, so it reads files with host I/O. The language itself
 * only does I/O through {@code TruffleLanguage.Env} (AGENTS.md).
 */
public final class ConformanceRunner {

    private static final String LANGUAGE = "scheme";
    private static final long TIMEOUT_SECONDS = Long.getLong("scheme.conformance.timeout", 120L);

    private ConformanceRunner() {
    }

    public static void main(String[] args) throws IOException {
        PrintStream stdout = System.out;
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "conformance-watchdog");
            t.setDaemon(true);
            return t;
        });
        try {
            List<String> libraryPath = new ArrayList<>();
            Path workingDirectory = null;
            List<String> arguments = new ArrayList<>();
            Map<String, String> environment = new LinkedHashMap<>();
            int i = 0;
            while (i < args.length) {
                String arg = args[i];
                if (i + 1 < args.length && arg.equals("-I")) {
                    libraryPath.add(args[i + 1]);
                    i += 2;
                } else if (i + 1 < args.length && arg.equals("-C")) {
                    workingDirectory = Path.of(args[i + 1]).toAbsolutePath();
                    i += 2;
                } else if (i + 1 < args.length && arg.equals("--arg")) {
                    arguments.add(args[i + 1]);
                    i += 2;
                } else if (i + 1 < args.length && arg.equals("--env")) {
                    String binding = args[i + 1];
                    int equals = binding.indexOf('=');
                    if (equals <= 0) {
                        throw new IllegalArgumentException("--env expects name=value: " + binding);
                    }
                    environment.put(binding.substring(0, equals), binding.substring(equals + 1));
                    i += 2;
                } else {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    String status = runSection(arg, libraryPath, workingDirectory, arguments, environment, output, watchdog);
                    byte[] bytes = output.toByteArray();
                    stdout.write(bytes);
                    if (bytes.length > 0 && bytes[bytes.length - 1] != '\n') {
                        stdout.println();
                    }
                    stdout.println("@@section " + arg + " " + status);
                    stdout.flush();
                    arguments.clear();
                    environment.clear();
                    i++;
                }
            }
        } finally {
            watchdog.shutdownNow();
        }
    }

    private static String runSection(String file, List<String> libraryPath, Path workingDirectory, List<String> arguments, Map<String, String> environment, ByteArrayOutputStream output,
                    ScheduledExecutorService watchdog) throws IOException {
        Source source = Source.newBuilder(LANGUAGE, new File(file)).build();
        List<String> commandLine = new ArrayList<>();
        commandLine.add(file);
        commandLine.addAll(arguments);
        Context.Builder builder = SchemeMain.newContext(libraryPath).arguments(LANGUAGE, commandLine.toArray(new String[0])).out(output).err(output);
        if (workingDirectory != null) {
            builder.currentWorkingDirectory(workingDirectory);
        }
        environment.forEach(builder::environment);
        try (Context context = builder.build()) {
            ScheduledFuture<?> timeout = watchdog.schedule(() -> context.close(true), TIMEOUT_SECONDS, TimeUnit.SECONDS);
            try {
                context.eval(source);
                return "ok";
            } finally {
                timeout.cancel(false);
            }
        } catch (PolyglotException e) {
            if (e.isExit()) {
                return "exit " + e.getExitStatus();
            }
            if (e.isCancelled()) {
                return "timeout after " + TIMEOUT_SECONDS + "s";
            }
            String kind = e.isInternalError() ? "internal error: " : "error: ";
            return kind + firstLine(e.getMessage());
        }
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "(no message)";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
