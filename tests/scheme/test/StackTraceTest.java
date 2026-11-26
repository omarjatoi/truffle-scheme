package scheme.test;

import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import scheme.launcher.SchemeMain;
import scheme.runtime.SchemeLanguage;

/**
 * M14-04: the guest stack trace and the location of an error, an exit and an emergency exit for
 * each way a frame can be off the Java stack or in the prelude (design.md D8, "Stack traces"). Each
 * case is a program in {@code tests/traces}, run with each raiser in place of {@code RAISE}: through
 * the embedder's {@code PolyglotException} and through the launcher's printed trace, in every mode.
 * The results are compared with {@code tests/traces/expected}: one file for the modes without
 * torture (jit, compile), one for both tortures, whose frames all wait on the heap.
 * {@code //tests:stack_traces_native} runs the same cases through the native launcher.
 */
public class StackTraceTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static final String SCHEME = "scheme";

    static final String[] CASES = {
                    "plain", "spill", "reentry-once", "reentry-twice", "reentry-inner", "callcc-receiver", "callcc-escape", "guard-reraise",
                    "wind-before", "wind-thunk", "wind-after", "handler-raise", "handler-continuable", "handler-intrinsic", "map", "for-each",
                    "apply", "cwv-producer", "cwv-consumer", "parameterize", "converter", "eval", "eval-datum", "load", "library",
                    "library-file", "after-replaces", "trampoline", "tail-caller",
    };

    /**
     * What replaces {@code RAISE}, and {@code PRIM} and {@code ARG} (the procedure and its argument,
     * for {@code (apply PRIM (list ARG))}): a primitive's error, a raise, an exit, an emergency exit.
     */
    static final String[][] RAISERS = {
                    {"error", "(car 'x)", "car", "'x"},
                    {"raise", "(raise 'boom)", "raise", "'boom"},
                    {"exit", "(exit 3)", "exit", "3"},
                    {"emergency-exit", "(emergency-exit 3)", "emergency-exit", "3"},
    };

    private static final boolean TORTURE = SchemeLanguage.constant(SchemeLanguage.TORTURE) != SchemeLanguage.Torture.OFF;

    private static String resource(String path) throws IOException {
        try (InputStream in = StackTraceTest.class.getResourceAsStream("/traces/" + path)) {
            if (in == null) {
                throw new IOException("missing test resource traces/" + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** A case's program, written with a raiser, and the launcher options its header asks for. */
    private record Program(Path file, List<String> options) {
    }

    private Program write(String name, String[] raiser) throws IOException {
        Path dir = folder.newFolder(name + "-" + raiser[0]).toPath();
        String text = resource(name + ".scm");
        List<String> options = new ArrayList<>();
        for (String line : text.lines().toList()) {
            if (line.startsWith("; options: ")) {
                for (String option : line.substring("; options: ".length()).split(" ")) {
                    options.add(option.replace("DIR", dir.toString()));
                }
            } else if (line.startsWith("; files: ")) {
                for (String file : line.substring("; files: ".length()).split(" ")) {
                    Path to = dir.resolve(file);
                    Files.createDirectories(to.getParent());
                    Files.writeString(to, substitute(resource(name + "/" + file), raiser, dir), StandardCharsets.UTF_8);
                }
            }
        }
        Path file = dir.resolve(name + ".scm");
        Files.writeString(file, substitute(text, raiser, dir), StandardCharsets.UTF_8);
        return new Program(file, options);
    }

    private static String substitute(String text, String[] raiser, Path dir) {
        return text.replace("RAISE", raiser[1]).replace("PRIM", raiser[2]).replace("ARG", raiser[3]).replace("DIR", dir.toString());
    }

    /** The case's run: its exception, or null if it ended. */
    private static PolyglotException run(Program program) throws IOException {
        Context.Builder builder = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).err(new ByteArrayOutputStream()).allowIO(IOAccess.ALL);
        for (int i = 0; i < program.options().size(); i++) {
            String option = program.options().get(i);
            if (option.equals("--experimental-options")) {
                builder.allowExperimentalOptions(true);
            } else if (option.equals("-I")) {
                builder.option("scheme.LibraryPath", program.options().get(++i));
            } else {
                String[] nameValue = option.substring(2).split("=", 2);
                builder.option(nameValue[0], nameValue[1]);
            }
        }
        try (Context context = builder.build()) {
            context.eval(Source.newBuilder(SCHEME, program.file().toFile()).build());
            return null;
        } catch (PolyglotException e) {
            return e;
        }
    }

    /** {@code PolyglotException}'s view: what it is, its location and its guest frames. */
    private static String embedder(PolyglotException e) {
        if (e == null) {
            return "no exception";
        }
        return (e.isExit() ? "exit " + e.getExitStatus() : "error") + " at " + at(e.getSourceLocation()) + " | " + frames(e);
    }

    private static String at(SourceSection s) {
        return s == null ? "none" : s.getSource().getName() + ":" + s.getStartLine() + " " + s.getCharacters().toString().replaceAll("\\s+", " ");
    }

    /**
     * The guest frames, innermost first, as {@code name file:line} ({@code ?} without a location),
     * {@code ~} before a frame whose source is internal (a prelude frame running resumed, which
     * Truffle shows: docs/upstream.md, "Continuation roots are never internal"), and a run of the
     * same frame once, with {@code xN}.
     */
    private static String frames(PolyglotException e) {
        List<String> frames = new ArrayList<>();
        String previous = null;
        int count = 0;
        for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
            if (!frame.isGuestFrame()) {
                continue;
            }
            SourceSection at = frame.getSourceLocation();
            String internal = at != null && at.getSource().isInternal() ? "~" : "";
            String text = internal + frame.getRootName() + " " + (at == null ? "?" : at.getSource().getName() + ":" + at.getStartLine());
            if (text.equals(previous)) {
                count++;
                continue;
            }
            if (previous != null) {
                frames.add(previous + (count > 1 ? " x" + count : ""));
            }
            previous = text;
            count = 1;
        }
        if (previous != null) {
            frames.add(previous + (count > 1 ? " x" + count : ""));
        }
        return String.join(", ", frames);
    }

    /**
     * The launcher's view (design.md D8, "Stack traces"): its exit status and the trace it prints
     * after the message, which is M14-07's ("Messages"), with the launcher's own code.
     */
    private static String printed(PolyglotException e) {
        if (e == null) {
            return "status 0";
        }
        if (e.isExit()) {
            return "status " + e.getExitStatus();
        }
        ByteArrayOutputStream trace = new ByteArrayOutputStream();
        SchemeMain.printStackTrace(e, new PrintStream(trace, true, StandardCharsets.UTF_8));
        StringBuilder text = new StringBuilder("status " + SchemeMain.EXIT_UNCAUGHT_ERROR);
        for (String line : trace.toString(StandardCharsets.UTF_8).lines().toList()) {
            text.append('\n').append(line);
        }
        return text.toString();
    }

    /**
     * Every case with every raiser, through the embedder and the launcher's printing, against
     * {@code tests/traces/expected}: on a difference, the test's output has each file as it would
     * be. {@code //tests:stack_traces} checks the same printed traces through the launcher's own
     * process, and {@code //tests:stack_traces_native} through the native launcher's.
     */
    @Test
    public void tracesAreTheExpectedOnes() throws IOException {
        StringBuilder embedder = new StringBuilder();
        StringBuilder printed = new StringBuilder();
        for (String name : CASES) {
            for (String[] raiser : RAISERS) {
                PolyglotException e = run(write(name, raiser));
                embedder.append(name).append(' ').append(raiser[0]).append(": ").append(embedder(e)).append('\n');
                printed.append(name).append(' ').append(raiser[0]).append(": ").append(printed(e)).append('\n');
            }
        }
        boolean embedderSame = compare("embedder", embedder.toString());
        boolean printedSame = compare("printed", printed.toString());
        assertTrue("tests/traces/expected differs: the test's output has each file as it would be", embedderSame && printedSame);
    }

    private static boolean compare(String kind, String actual) throws IOException {
        String file = "expected/" + kind + (TORTURE ? "-torture" : "") + ".txt";
        if (resource(file).equals(actual)) {
            return true;
        }
        System.out.println("=== tests/traces/" + file + " would be:\n" + actual + "=== end");
        return false;
    }
}
