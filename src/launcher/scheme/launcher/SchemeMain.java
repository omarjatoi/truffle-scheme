package scheme.launcher;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;

/**
 * Runs one Scheme program file: {@code scheme.launcher.SchemeMain file.scm}. Used by
 * {@code mx r7rs-diff}, which compares its stdout and exit status with chibi-scheme's (design.md
 * D8), so the exit statuses follow chibi-scheme's:
 *
 * <ul>
 * <li>0 when the program finishes.</li>
 * <li>The program's status when it calls {@code exit} (Truffle context exit).</li>
 * <li>{@value #EXIT_UNCAUGHT_ERROR} (EX_SOFTWARE) for an uncaught error. The message goes to
 * stderr; differential tests never compare its wording.</li>
 * </ul>
 */
public final class SchemeMain {

    public static final String LANGUAGE = "scheme";
    /** chibi-scheme's status for an uncaught error. */
    public static final int EXIT_UNCAUGHT_ERROR = 70;
    /** EX_USAGE. */
    public static final int EXIT_USAGE = 64;
    /** EX_NOINPUT. */
    public static final int EXIT_NO_INPUT = 66;

    private SchemeMain() {
    }

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        System.out.flush();
        System.exit(status);
    }

    public static int run(String[] args, OutputStream out, OutputStream err) {
        PrintStream errors = new PrintStream(err, true, StandardCharsets.UTF_8);
        if (args.length != 1) {
            errors.println("usage: scheme.launcher.SchemeMain <file.scm>");
            return EXIT_USAGE;
        }
        Source source;
        try {
            source = Source.newBuilder(LANGUAGE, new File(args[0])).build();
        } catch (IOException e) {
            errors.println("ERROR: cannot read " + args[0] + ": " + e.getMessage());
            return EXIT_NO_INPUT;
        }
        try (Context context = Context.newBuilder(LANGUAGE).out(out).err(err).build()) {
            context.eval(source);
            return 0;
        } catch (PolyglotException e) {
            if (e.isExit()) {
                return e.getExitStatus();
            }
            if (e.isInternalError()) {
                // Internal errors are bugs and are never turned into Scheme errors (design.md D8).
                errors.println("INTERNAL ERROR: " + e.getMessage());
                e.printStackTrace(errors);
            } else {
                errors.println("ERROR: " + e.getMessage());
            }
            return EXIT_UNCAUGHT_ERROR;
        }
    }
}
