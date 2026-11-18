package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.io.IOException;

import org.graalvm.polyglot.Source;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The language's file type detector (docs/graalvm-configuration.md, "The language's
 * registration"): Scheme programs and libraries are found by their extension.
 */
public class FileTypeDetectorTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void schemeFilesAreFoundByTheirExtension() throws IOException {
        for (String name : new String[]{"program.scm", "library.sld", "SHOUT.SCM"}) {
            File file = folder.newFile(name);
            assertEquals(name, "scheme", Source.findLanguage(file));
            assertEquals(name, "application/x-scheme", Source.findMimeType(file));
            assertEquals(name, "application/x-scheme", Source.newBuilder("scheme", file).build().getMimeType());
        }
        assertNull(Source.findLanguage(folder.newFile("notes.txt")));
    }
}
