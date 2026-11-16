package scheme.driver;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test: a program's stdout and exit status, interpreted and with the JIT, under JVM limits.
 *
 * <p>
 * {@code --kind=spec}: a tests/spec case, with its {@code ;; limits:} and {@code ;; exit:} headers
 * and its .out file (tests/spec/README.md). {@code --kind=space --expect=LINE --limits=OPTIONS}:
 * one line of stdout expected under those limits.
 */
public final class Program {

    private static final Pattern HEADER = Pattern.compile("^;;\\s*(limits|exit):\\s*(.*?)\\s*$");

    private Program() {
    }

    public static void main(String[] args) throws Exception {
        Launch launch = new Launch(args);
        Path file = Launch.runfile(launch.option("file"));
        List<String> limits = new ArrayList<>();
        int status = 0;
        String expected;
        if (launch.option("kind").equals("space")) {
            limits.addAll(Arrays.asList(launch.option("limits").split(" ")));
            expected = launch.option("expect") + "\n";
        } else {
            for (String line : Launch.read(file).split("\n")) {
                if (!line.startsWith(";;")) {
                    break;
                }
                Matcher m = HEADER.matcher(line);
                if (m.matches() && m.group(1).equals("limits")) {
                    limits.addAll(Arrays.asList(m.group(2).split(" ")));
                } else if (m.matches()) {
                    status = Integer.parseInt(m.group(2));
                }
            }
            String name = file.getFileName().toString();
            expected = Launch.read(file.resolveSibling(name.substring(0, name.length() - ".scm".length()) + ".out"));
        }
        List<String> problems = new ArrayList<>();
        for (boolean interpreted : new boolean[]{true, false}) {
            String label = interpreted ? "interpreter" : "JIT";
            List<String> jvmArgs = new ArrayList<>(limits);
            if (interpreted) {
                jvmArgs.addAll(Launch.INTERPRETER_VM_ARGS);
            }
            long start = System.nanoTime();
            Launch.Result r = launch.run(jvmArgs, Launch.LAUNCHER_MAIN, List.of(file.toString()), null, Map.of(), 900);
            double seconds = (System.nanoTime() - start) / 1e9;
            if (r.status() != status || !r.out().equals(expected)) {
                String detail = r.status() != status ? "exit " + r.status() + " (expected " + status + ")" : "stdout differs";
                if (!r.out().equals(expected)) {
                    detail += "\n" + Launch.firstDifference(expected, r.out(), "expected", "actual");
                }
                problems.add(String.format(Locale.ROOT, "%s (%.1fs): %s%n  stderr:%n%s", label, seconds, detail, Launch.tail(r.err(), 5)));
            } else {
                Launch.log(String.format(Locale.ROOT, "%s: %s ok (%.1fs, %s)", file.getFileName(), label, seconds, String.join(" ", limits)));
            }
        }
        if (!problems.isEmpty()) {
            throw Launch.fail("FAIL " + file + " " + String.join(" ", limits) + "\n  " + String.join("\n  ", problems));
        }
    }
}
