package scheme.driver.junit;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.internal.TextListener;
import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

/**
 * Runs one JUnit class as {@code JUnitCore} does, and writes its results as JUnit XML to
 * {@code $XML_OUTPUT_FILE}: a test case per test method, with its failure, error or skip. Without
 * the file, Bazel 9 generates one in a second spawn (design.md D11, "Bazel 9"; bazel/jvm_test.bzl
 * junit_test).
 *
 * <p>
 * {@code XmlRunner CLASS}
 */
public final class XmlRunner {

    private XmlRunner() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: XmlRunner CLASS");
            System.exit(2);
        }
        Recorder recorder = new Recorder();
        boolean passed;
        long start = System.nanoTime();
        try {
            JUnitCore core = new JUnitCore();
            core.addListener(new TextListener(System.out));
            core.addListener(recorder);
            Result result = core.run(Class.forName(args[0]));
            passed = result.wasSuccessful();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            recorder.testFailure(new Failure(Description.createSuiteDescription(args[0]), e));
            passed = false;
        }
        String xml = System.getenv("XML_OUTPUT_FILE");
        if (xml != null && !xml.isEmpty()) {
            Files.writeString(Path.of(xml), recorder.xml(args[0], seconds(System.nanoTime() - start)), StandardCharsets.UTF_8);
        }
        System.exit(passed ? 0 : 1);
    }

    private static double seconds(long nanos) {
        return nanos / 1e9;
    }

    /** One test case: a test method, or the class itself when it fails outside its methods. */
    private static final class Case {
        final Description description;
        final long start = System.nanoTime();
        double time;
        /** "failure", "error" or "skipped", or null when it passed. */
        String outcome;
        String type;
        String message;
        String detail;

        Case(Description description) {
            this.description = description;
        }
    }

    @RunListener.ThreadSafe
    private static final class Recorder extends RunListener {
        private final Map<Description, Case> cases = new LinkedHashMap<>();

        private synchronized Case get(Description description) {
            return cases.computeIfAbsent(description, Case::new);
        }

        @Override
        public void testStarted(Description description) {
            get(description);
        }

        @Override
        public void testFinished(Description description) {
            Case c = get(description);
            c.time = seconds(System.nanoTime() - c.start);
        }

        @Override
        public void testFailure(Failure failure) {
            Throwable thrown = failure.getException();
            record(failure, thrown instanceof AssertionError ? "failure" : "error");
        }

        @Override
        public void testAssumptionFailure(Failure failure) {
            record(failure, "skipped");
        }

        @Override
        public void testIgnored(Description description) {
            get(description).outcome = "skipped";
        }

        private synchronized void record(Failure failure, String outcome) {
            Case c = get(failure.getDescription());
            if (c.outcome == null || c.outcome.equals("skipped")) {
                c.outcome = outcome;
                Throwable thrown = failure.getException();
                c.type = thrown == null ? null : thrown.getClass().getName();
                c.message = thrown == null ? null : thrown.getMessage();
                c.detail = outcome.equals("skipped") ? "" : trace(thrown);
            } else {
                c.detail = c.detail + "\n" + trace(failure.getException());
            }
        }

        synchronized String xml(String suite, double time) {
            List<String> lines = new ArrayList<>();
            int failures = 0;
            int errors = 0;
            int skipped = 0;
            for (Case c : cases.values()) {
                String method = c.description.getMethodName();
                String name = method != null ? method : c.description.getDisplayName();
                String className = c.description.getClassName();
                String open = String.format(Locale.ROOT, "    <testcase name=%s classname=%s time=\"%.3f\"", attribute(name), attribute(className), c.time);
                if (c.outcome == null) {
                    lines.add(open + "/>");
                    continue;
                }
                switch (c.outcome) {
                    case "failure" -> failures++;
                    case "error" -> errors++;
                    default -> skipped++;
                }
                lines.add(open + ">");
                String message = c.message == null ? "" : " message=" + attribute(c.message);
                String type = c.outcome.equals("skipped") || c.type == null ? "" : " type=" + attribute(c.type);
                if (c.detail == null || c.detail.isEmpty()) {
                    lines.add("      <" + c.outcome + message + type + "/>");
                } else {
                    lines.add("      <" + c.outcome + message + type + ">" + text(c.detail) + "</" + c.outcome + ">");
                }
                lines.add("    </testcase>");
            }
            StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuites>\n");
            b.append(String.format(Locale.ROOT, "  <testsuite name=%s tests=\"%d\" failures=\"%d\" errors=\"%d\" skipped=\"%d\" time=\"%.3f\">%n",
                            attribute(suite), cases.size(), failures, errors, skipped, time));
            for (String line : lines) {
                b.append(line).append('\n');
            }
            return b.append("  </testsuite>\n</testsuites>\n").toString();
        }
    }

    private static String trace(Throwable thrown) {
        if (thrown == null) {
            return "";
        }
        StringWriter out = new StringWriter();
        thrown.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    private static String attribute(String s) {
        return "\"" + text(s).replace("\"", "&quot;").replace("\n", "&#10;") + "\"";
    }

    /** Character data: markup escaped, and characters XML 1.0 can't hold as {@code ?}. */
    private static String text(String s) {
        StringBuilder b = new StringBuilder(s.length());
        s.codePoints().forEach(c -> {
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                default -> {
                    boolean valid = c == '\t' || c == '\n' || c == '\r' || (c >= 0x20 && c <= 0xD7FF) || (c >= 0xE000 && c <= 0xFFFD) || c >= 0x10000;
                    b.appendCodePoint(valid ? c : '?');
                }
            }
        });
        return b.toString();
    }
}
