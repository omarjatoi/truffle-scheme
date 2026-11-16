package scheme.compiler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.source.Source;

import scheme.runtime.SchemeError;
import scheme.runtime.SchemeLanguage;

/**
 * Where {@code include} and library lookup find files: a directory read through
 * the language {@code Env} (design.md D10), or the prelude's bundled resources.
 */
abstract class SourceDir {

    /** The file at {@code relative}, or null if there is none. */
    abstract Source find(String relative);

    /** The directory that files included from {@code source} are relative to. */
    abstract SourceDir dirOf(Source source);

    /**
     * The prelude's resources (src/prelude/scheme/prelude): the implementation's own bundled sources.
     * Each lookup is remembered, so a file is read once, and {@link PreludeImage} knows which files
     * the standard libraries were made from.
     */
    static final class Resources extends SourceDir {
        private static final String ROOT = "/scheme/prelude/";

        /** Every file looked up so far, by name: its Source, or null if there is no such file. */
        private final Map<String, Source> read;

        Resources(Map<String, Source> read) {
            this.read = read;
        }

        /** The resource's bytes, or null if there is none. */
        @TruffleBoundary
        static byte[] bytes(String relative) {
            try (InputStream in = SourceDir.class.getResourceAsStream(ROOT + relative)) {
                return in == null ? null : in.readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException("cannot read prelude resource " + relative, e);
            }
        }

        /** The Source of the resource with these bytes. */
        @TruffleBoundary
        static Source source(String relative, byte[] bytes) {
            return Source.newBuilder(SchemeLanguage.ID, new String(bytes, StandardCharsets.UTF_8), "prelude/" + relative).internal(true).build();
        }

        /** Every file looked up so far. */
        Map<String, Source> read() {
            return read;
        }

        @Override
        Source find(String relative) {
            if (read.containsKey(relative)) {
                return read.get(relative);
            }
            byte[] bytes = bytes(relative);
            Source source = bytes == null ? null : source(relative, bytes);
            read.put(relative, source);
            return source;
        }

        @Override
        SourceDir dirOf(Source source) {
            return this;
        }
    }

    /** A directory; null means the current working directory. */
    static final class Files extends SourceDir {
        private final TruffleLanguage.Env env;
        private final TruffleFile dir;

        Files(TruffleLanguage.Env env, TruffleFile dir) {
            this.env = env;
            this.dir = dir;
        }

        @Override
        Source find(String relative) {
            try {
                TruffleFile file = dir == null ? env.getPublicTruffleFile(relative) : dir.resolve(relative);
                if (!file.isRegularFile()) {
                    return null;
                }
                return Source.newBuilder(SchemeLanguage.ID, file).build();
            } catch (IOException | SecurityException | UnsupportedOperationException e) {
                throw new SchemeError("cannot read " + relative + ": " + e.getMessage());
            }
        }

        @Override
        SourceDir dirOf(Source source) {
            if (source.getPath() == null) {
                return this;
            }
            return new Files(env, env.getPublicTruffleFile(source.getPath()).getParent());
        }
    }
}
