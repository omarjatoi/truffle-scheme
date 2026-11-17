package scheme.driver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * What the test drivers share: their options, and running a JVM of the language (the launcher, or
 * the conformance runner) as a child process of the same JDK, as tests/harness/harness.py's
 * Runtime does.
 */
final class Launch {

    /** Truffle on JDK 25 uses restricted native access and sun.misc.Unsafe; so does JLine. */
    static final List<String> TRUFFLE_VM_ARGS = List.of(
                    "--enable-native-access=org.graalvm.truffle,org.graalvm.shadowed.jline",
                    "--sun-misc-unsafe-memory-access=allow");
    /** Compile immediately; compilation failures are errors. */
    static final List<String> COMPILE_VM_ARGS = List.of(
                    "-Dpolyglot.engine.AllowExperimentalOptions=true",
                    "-Dpolyglot.engine.CompileImmediately=true",
                    "-Dpolyglot.engine.BackgroundCompilation=false",
                    "-Dpolyglot.engine.CompilationFailureAction=Throw");
    /** Interpreter only. */
    static final List<String> INTERPRETER_VM_ARGS = List.of(
                    "-Dpolyglot.engine.AllowExperimentalOptions=true",
                    "-Dpolyglot.engine.Compilation=false");
    static final String TORTURE_VM_ARG = "-Dscheme.torture=true";
    /** With torture: its captures stay owned, so their frames are resumed in place (design.md "Owned segments"). */
    static final String TORTURE_OWNED_VM_ARG = "-Dscheme.torture.owned=true";
    static final String LAUNCHER_MAIN = "scheme.launcher/scheme.launcher.SchemeMain";

    final String modulePath;
    final List<String> rest = new ArrayList<>();
    final Map<String, String> options = new java.util.HashMap<>();

    /** Reads `--name=value` options (paths are runfiles paths) and the other arguments. */
    Launch(String[] args) {
        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (arg.startsWith("--") && eq > 0) {
                options.put(arg.substring(2, eq), arg.substring(eq + 1));
            } else {
                rest.add(arg);
            }
        }
        this.modulePath = classPath("module-path");
    }

    /** A `:`-separated list of runfiles paths, as absolute paths. */
    String classPath(String name) {
        List<String> jars = new ArrayList<>();
        for (String jar : options.getOrDefault(name, "").split(":")) {
            if (!jar.isEmpty()) {
                jars.add(runfile(jar).toString());
            }
        }
        return String.join(":", jars);
    }

    String option(String name) {
        String value = options.get(name);
        if (value == null) {
            throw fail("missing option --" + name);
        }
        return value;
    }

    /** A runfiles path, as `$(rlocationpath ...)` gives it. */
    static Path runfile(String path) {
        return Path.of(System.getenv("TEST_SRCDIR"), path);
    }

    static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * The JVM options of a mode: interpreted unless compiled, and under {@code torture}: null (none),
     * "torture" (every capture shared) or "torture_owned" (captures owned, resumed in place).
     */
    static List<String> vmArgs(boolean compile, String torture) {
        List<String> args = new ArrayList<>(compile ? COMPILE_VM_ARGS : INTERPRETER_VM_ARGS);
        if (torture != null) {
            args.add(TORTURE_VM_ARG);
            switch (torture) {
                case "torture" -> {
                }
                case "torture_owned" -> args.add(TORTURE_OWNED_VM_ARG);
                default -> throw fail("unknown torture mode " + torture);
            }
        }
        return args;
    }

    /** A child JVM's exit status, stdout and stderr. */
    record Result(int status, String out, String err) {
    }

    /** Runs a tool's main class in a child JVM of this JDK on a class path; its output is this test's. */
    static int runTool(List<String> jvmArgs, String classPath, String main, List<String> args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Djava.io.tmpdir=" + System.getenv("TEST_TMPDIR"));
        command.addAll(jvmArgs);
        command.addAll(List.of("-cp", classPath, main));
        command.addAll(args);
        log(String.join(" ", command));
        return new ProcessBuilder(command).inheritIO().start().waitFor();
    }

    /** Runs `main` (module/class) in a child JVM of this JDK with the module path. */
    Result run(List<String> jvmArgs, String main, List<String> args, Path dir, Map<String, String> env, long timeoutSeconds)
                    throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Djava.io.tmpdir=" + System.getenv("TEST_TMPDIR"));
        command.addAll(jvmArgs);
        command.addAll(List.of("-p", modulePath, "--add-modules", "ALL-MODULE-PATH"));
        command.addAll(TRUFFLE_VM_ARGS);
        command.addAll(List.of("-m", main));
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
        if (dir != null) {
            builder.directory(dir.toFile());
        }
        builder.environment().putAll(env);
        Process process = builder.start();
        CompletableFuture<String> out = drain(process.getInputStream());
        CompletableFuture<String> err = drain(process.getErrorStream());
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw fail("timed out after " + timeoutSeconds + " s: " + String.join(" ", command));
        }
        return new Result(process.exitValue(), out.join(), err.join());
    }

    private static CompletableFuture<String> drain(InputStream in) {
        return CompletableFuture.supplyAsync(() -> {
            try (in) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "(" + e + ")";
            }
        });
    }

    /** The last lines of a text, indented. */
    static String tail(String text, int lines) {
        String[] all = text.strip().split("\n");
        StringBuilder b = new StringBuilder();
        for (int i = Math.max(0, all.length - lines); i < all.length; i++) {
            b.append("    ").append(all[i]).append('\n');
        }
        return b.toString();
    }

    /** Where two texts first differ, line by line. */
    static String firstDifference(String expected, String actual, String expectedName, String actualName) {
        String[] e = expected.split("\n", -1);
        String[] a = actual.split("\n", -1);
        for (int i = 0; i < Math.max(e.length, a.length); i++) {
            String el = i < e.length ? e[i] : "(end)";
            String al = i < a.length ? a[i] : "(end)";
            if (!el.equals(al)) {
                return "  line " + (i + 1) + ":\n    " + expectedName + ": " + el + "\n    " + actualName + ": " + al;
            }
        }
        return "  (the same lines)";
    }

    static RuntimeException fail(String message) {
        System.err.println(message);
        System.exit(1);
        return new IllegalStateException(message);
    }

    static void log(String message) {
        System.out.println(message);
    }
}
