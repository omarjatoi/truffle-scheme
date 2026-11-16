import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;

/**
 * The Eclipse Java formatter as GraalVM's projects run it (Eclipse's JavaCodeFormatter
 * application, then trailing whitespace removal), without an Eclipse installation: JDT's
 * formatter from Maven, with GraalVM's settings (bazel/lint/org.eclipse.jdt.core.prefs).
 *
 * <pre>
 * EclipseFormat --check|--fix PREFS FILE...
 * </pre>
 *
 * {@code --check} prints how each file would change and exits 1 if any would; {@code --fix}
 * rewrites them.
 */
public final class EclipseFormat {

    private EclipseFormat() {
    }

    public static void main(String[] args) throws IOException, BadLocationException {
        boolean fix = args[0].equals("--fix");
        Properties prefs = new Properties();
        try (InputStream in = Files.newInputStream(Path.of(args[1]))) {
            prefs.load(in);
        }
        Map<String, String> options = new HashMap<>();
        prefs.forEach((k, v) -> options.put((String) k, (String) v));
        CodeFormatter formatter = ToolFactory.createCodeFormatter(options, ToolFactory.M_FORMAT_EXISTING);
        List<String> changed = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            Path file = Path.of(args[i]);
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Document document = new Document(source);
            TextEdit edit = formatter.format(CodeFormatter.K_COMPILATION_UNIT | CodeFormatter.F_INCLUDE_COMMENTS, source, 0, source.length(), 0, "\n");
            if (edit == null) {
                System.out.println(file + ": the formatter cannot parse it");
                changed.add(args[i]);
                continue;
            }
            edit.apply(document);
            // sp_cleanup.remove_trailing_whitespaces_all=true in GraalVM's jdt.ui settings.
            String formatted = document.get().replaceAll("(?m)[ \t]+$", "");
            if (!formatted.equals(source)) {
                changed.add(args[i]);
                if (fix) {
                    Files.writeString(file, formatted, StandardCharsets.UTF_8);
                } else {
                    System.out.println(diff(args[i], source, formatted));
                }
            }
        }
        if (!changed.isEmpty() && !fix) {
            System.out.println(changed.size() + " file(s) are not formatted. Run `bazel run //:r7rs -- format` and commit the changes:");
            changed.forEach(f -> System.out.println("  " + f));
            System.exit(1);
        }
        if (fix) {
            System.out.println("formatted " + changed.size() + " file(s)" + (changed.isEmpty() ? "" : ": " + String.join(" ", changed)));
        }
    }

    /** The first lines that differ, enough to see what the formatter wants. */
    private static String diff(String name, String before, String after) {
        String[] a = before.split("\n", -1);
        String[] b = after.split("\n", -1);
        int start = 0;
        while (start < a.length && start < b.length && a[start].equals(b[start])) {
            start++;
        }
        StringBuilder out = new StringBuilder("--- " + name + " (line " + (start + 1) + ")\n");
        for (int i = start; i < Math.min(a.length, start + 6); i++) {
            out.append("- ").append(a[i]).append('\n');
        }
        for (int i = start; i < Math.min(b.length, start + 6); i++) {
            out.append("+ ").append(b[i]).append('\n');
        }
        return out.toString();
    }
}
