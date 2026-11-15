package scheme.launcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;

/**
 * Runs conformance sections (tests/r7rs/*.scm) in one JVM, each in a fresh context, for
 * {@code mx r7rs-test} and {@code mx r7rs-status}: {@code ConformanceRunner [-I dir]... file...}.
 *
 * <p>For each file it prints the section's own output (the {@code @@r7rs-test} lines from the
 * (chibi test) shim in tests/lib) followed by one summary line:
 * {@code @@section <file> <ok | error: ... | timeout ...>}. The mx side counts the result lines.
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
            int start = 0;
            while (start + 1 < args.length && args[start].equals("-I")) {
                libraryPath.add(args[start + 1]);
                start += 2;
            }
            for (String file : Arrays.asList(args).subList(start, args.length)) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                String status = runSection(file, libraryPath, output, watchdog);
                byte[] bytes = output.toByteArray();
                stdout.write(bytes);
                if (bytes.length > 0 && bytes[bytes.length - 1] != '\n') {
                    stdout.println();
                }
                stdout.println("@@section " + file + " " + status);
                stdout.flush();
            }
        } finally {
            watchdog.shutdownNow();
        }
    }

    private static String runSection(String file, List<String> libraryPath, ByteArrayOutputStream output, ScheduledExecutorService watchdog) throws IOException {
        Source source = Source.newBuilder(LANGUAGE, new File(file)).build();
        try (Context context = SchemeMain.newContext(libraryPath).out(output).err(output).build()) {
            ScheduledFuture<?> timeout = watchdog.schedule(() -> context.close(true), TIMEOUT_SECONDS, TimeUnit.SECONDS);
            try {
                context.eval(source);
                return "ok";
            } finally {
                timeout.cancel(false);
            }
        } catch (PolyglotException e) {
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
