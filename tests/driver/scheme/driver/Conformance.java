package scheme.driver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test: one section of a conformance suite, interpreted, compiled or under torture (either kind:
 * {@link Launch#vmArgs}), passes every
 * test but the ones the suite lists as exceptions (tests/r7rs-larceny/README.md), and those fail.
 * Writes its counts to result.json among the test's outputs ({@code r7rs -- conformance-report}).
 *
 * <p>
 * {@code --module-path=JARS --suite=chibi|larceny --suite-dir=DIR --lib=DIR --section=ID
 * --mode=interpreter|compile|torture|torture_owned}
 */
public final class Conformance {

    private static final Pattern RESULT_LINE = Pattern.compile("^@@r7rs-test (BEGIN|PASS|FAIL|ERROR|SKIP|END) ?(.*)$");
    private static final Pattern SECTION_LINE = Pattern.compile("^@@section (\\S+) (.*)$");
    /** Sections whose results depend on speed, so they always run compiled (harness.py LARCENY_JIT). */
    private static final Set<String> LARCENY_JIT = Set.of("time");
    /** Line breaks as Python's str.splitlines() (the harness's) sees them. */
    private static final Pattern LINE_BREAK = Pattern.compile("\r\n|[\n\r\u000B\u000C\u001C\u001D\u001E\u0085\u2028\u2029]");

    private Conformance() {
    }

    record Section(String id, String title, Path file, int total, List<String> args, List<String> env, Integer exitStatus) {
    }

    public static void main(String[] args) throws Exception {
        Launch launch = new Launch(args);
        String suite = launch.option("suite");
        Path dir = Launch.runfile(launch.option("suite-dir"));
        Path lib = Launch.runfile(launch.option("lib"));
        String mode = launch.option("mode");
        Section section = section(suite, dir, launch.option("section"));
        List<String> listed = exceptions(suite, dir).getOrDefault(section.id(), List.of());

        Path workdir = Files.createTempDirectory(Path.of(System.getenv("TEST_TMPDIR")), "r7rs-" + suite + "-");
        List<String> runner = new ArrayList<>(List.of("-I", lib.toString()));
        if (suite.equals("larceny")) {
            // The suite loads tests/scheme/*.scm relative to its working directory and writes scratch
            // files there: a fresh directory whose tests/ is the vendored one.
            Files.createSymbolicLink(workdir.resolve("tests"), dir.resolve("tests"));
            runner.addAll(List.of("-I", dir.toString(), "-C", workdir.toString()));
        }
        for (String arg : section.args()) {
            runner.addAll(List.of("--arg", arg));
        }
        for (String binding : section.env()) {
            runner.addAll(List.of("--env", binding));
        }
        runner.add(section.file().toString());
        boolean jit = suite.equals("larceny") && LARCENY_JIT.contains(section.id());
        Launch.Result r = launch.run(Launch.vmArgs(mode.equals("compile") || jit, mode.startsWith("torture") ? mode : null),
                        "scheme.launcher/scheme.launcher.ConformanceRunner", runner, null, Map.of(), 900);
        String output = r.out() + r.err();

        // The section's output ends at its @@section line, which carries the runner's message.
        StringBuilder current = new StringBuilder();
        String message = null;
        for (String line : LINE_BREAK.split(output, -1)) {
            Matcher m = SECTION_LINE.matcher(line);
            if (m.matches() && message == null) {
                message = m.group(2);
            } else if (message == null) {
                current.append(line).append('\n');
            }
        }
        if (r.status() != 0 || message == null) {
            throw Launch.fail("ConformanceRunner failed (exit " + r.status() + "); no result for " + section.id() + ":\n" + output);
        }
        int pass = 0;
        int depth = 0;
        boolean complete = false;
        List<String> failing = new ArrayList<>();
        for (String line : LINE_BREAK.split(current)) {
            Matcher m = RESULT_LINE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            switch (m.group(1)) {
                case "BEGIN" -> depth++;
                case "END" -> {
                    depth--;
                    complete = depth == 0;
                }
                case "PASS" -> pass++;
                case "FAIL", "ERROR" -> failing.add(m.group(2).split(" :: ", 2)[0]);
                default -> {
                }
            }
        }
        if (section.exitStatus() != null) {
            complete = message.equals("exit " + section.exitStatus());
            if (complete) {
                pass++;
            }
        }

        List<String> unexpected = new ArrayList<>(failing);
        List<String> nowPassing = new ArrayList<>();
        for (String test : listed) {
            if (!unexpected.remove(test)) {
                nowPassing.add(test);
            }
        }
        int expected = section.total() - listed.size();
        String json = String.format(Locale.ROOT, "{\"complete\": %s, \"expected\": %d, \"message\": \"%s\", \"mode\": \"%s\", \"pass\": %d, " +
                        "\"section\": \"%s\", \"suite\": \"%s\", \"title\": \"%s\", \"total\": %d}%n",
                        complete, expected, escape(message), mode, pass, escape(section.id()), suite, escape(section.title()), section.total());
        String outputs = System.getenv("TEST_UNDECLARED_OUTPUTS_DIR");
        if (outputs != null) {
            Files.writeString(Path.of(outputs, "result.json"), json);
        }
        Launch.log(suite + " " + section.id() + " (" + mode + "): " + pass + "/" + section.total() + ", " + expected + " expected");

        List<String> problems = new ArrayList<>();
        if (!complete) {
            problems.add("aborted: " + message);
        }
        if (!unexpected.isEmpty()) {
            problems.add("these tests fail, and the suite doesn't list them as exceptions:\n    " + String.join("\n    ", unexpected));
        }
        if (!nowPassing.isEmpty()) {
            problems.add("these tests pass now, but tests/r7rs-larceny/README.md lists them as exceptions (expected failures): " +
                            "remove their rows from its Exceptions table\n    " + String.join("\n    ", nowPassing));
        }
        if (pass != expected && problems.isEmpty()) {
            problems.add(pass + " tests pass, " + expected + " expected");
        }
        if (!problems.isEmpty()) {
            throw Launch.fail(suite + " " + section.id() + " (" + mode + "): " + String.join("\n", problems) + "\n\n" + Launch.tail(output, 40));
        }
    }

    /** A section of a suite, from its sections.tsv. */
    static Section section(String suite, Path dir, String id) throws java.io.IOException {
        for (String line : Launch.read(dir.resolve("sections.tsv")).split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            if (!f[0].equals(id)) {
                continue;
            }
            if (suite.equals("chibi")) {
                return new Section(f[0], f[1], dir.resolve(f[0] + ".scm"), Integer.parseInt(f[2]), List.of(), List.of(), null);
            }
            return new Section(f[0], f[1], dir.resolve("tests/scheme/run/" + f[2] + ".sps"), Integer.parseInt(f[6]), words(f[3]), words(f[4]),
                            f[5].equals("-") ? null : Integer.valueOf(f[5]));
        }
        throw Launch.fail(suite + " has no section " + id);
    }

    private static List<String> words(String field) {
        return field.equals("-") ? List.of() : List.of(field.split(" "));
    }

    /** The README's Exceptions table: | `section` | `test` | Larceny's result | R7RS text |. */
    static Map<String, List<String>> exceptions(String suite, Path dir) throws java.io.IOException {
        Map<String, List<String>> listed = new HashMap<>();
        if (!suite.equals("larceny")) {
            return listed;
        }
        boolean inTable = false;
        for (String line : Launch.read(dir.resolve("README.md")).split("\n")) {
            if (line.startsWith("## ")) {
                inTable = line.strip().equals("## Exceptions");
            } else if (inTable && line.startsWith("| `")) {
                String[] cells = line.strip().replaceAll("^\\||\\|$", "").split(" \\| ");
                String section = strip(cells[0]);
                listed.putIfAbsent(section, new ArrayList<>());
                listed.get(section).add(strip(cells[1]));
            }
        }
        return listed;
    }

    private static String strip(String cell) {
        return cell.strip().replaceAll("^`|`$", "");
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
