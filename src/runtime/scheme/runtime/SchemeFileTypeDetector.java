package scheme.runtime;

import java.nio.charset.Charset;
import java.util.Locale;

import com.oracle.truffle.api.TruffleFile;

/**
 * Scheme's files by their extension: programs ({@code .scm}) and libraries ({@code .sld}) are
 * {@link SchemeLanguage#MIME_TYPE}, so {@code Source.findLanguage(file)} answers {@code scheme} and
 * a {@code Source} of such a file has the language's MIME type (docs/graalvm-configuration.md, "The
 * language's registration"). Their encoding is the default, UTF-8.
 */
public final class SchemeFileTypeDetector implements TruffleFile.FileTypeDetector {

    @Override
    public String findMimeType(TruffleFile file) {
        String name = file.getName();
        if (name == null) {
            return null;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".scm") || lower.endsWith(".sld") ? SchemeLanguage.MIME_TYPE : null;
    }

    @Override
    public Charset findEncoding(TruffleFile file) {
        return null;
    }
}
