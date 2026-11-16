package scheme.compiler;

import java.io.IOException;
import java.util.function.Function;

/**
 * The build-time prelude image (design.md D13), for tests and tools: the image the language
 * bundles, and whether an image is up to date with the prelude's files.
 */
public final class PreludeImageCheck {

    private PreludeImageCheck() {
    }

    /** The bundled build-time image's bytes, or null if there is none. */
    public static byte[] bundled() {
        return SourceDir.Resources.bytes(PreludeImage.RESOURCE);
    }

    /** A prelude file the language bundles, by its name in the prelude, or null if there is none. */
    public static byte[] preludeFile(String name) {
        return SourceDir.Resources.bytes(name);
    }

    /**
     * Why the language wouldn't use {@code image} if its prelude's files were {@code files} (by
     * name, null for a file that doesn't exist), or null if it would. The primitives an image was
     * made with are checked when a context restores from it.
     */
    public static String staleness(byte[] image, Function<String, byte[]> files) {
        try {
            return PreludeImage.fromBytes(image, files).problem();
        } catch (IOException | RuntimeException e) {
            return "it is corrupt (" + e + ")";
        }
    }
}
