package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

import scheme.launcher.SchemeMain;

/**
 * M1-02: the launcher that the differential tests compares against chibi-scheme. Exit statuses follow
 * chibi's: 0 on success, 70 for an uncaught error (design.md D8).
 */
public class SchemeMainTest {

    private record Result(int status, String out, String err) {
    }

    private static Result run(String program) throws IOException {
        Path file = Files.createTempFile("scheme-main", ".scm");
        try {
            Files.writeString(file, program);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int status = SchemeMain.run(new String[]{file.toString()}, out, err);
            return new Result(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            Files.delete(file);
        }
    }

    @Test
    public void successfulProgramExitsZeroWithNoOutput() throws IOException {
        Result r = run("42\n");
        assertEquals(0, r.status());
        assertEquals("", r.out());
    }

    @Test
    public void uncaughtErrorExitsWithChibisStatus() throws IOException {
        Result r = run("(car 1)\n");
        assertEquals(SchemeMain.EXIT_UNCAUGHT_ERROR, r.status());
        assertEquals(70, SchemeMain.EXIT_UNCAUGHT_ERROR);
        assertEquals("", r.out());
        assertTrue("error message goes to stderr: " + r.err(), r.err().startsWith("ERROR: "));
    }

    /** M6-01: with no file the launcher starts a REPL (LauncherTest), so a usage error is a bad -I. */
    @Test
    public void usageErrorExitsWithExUsage() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(SchemeMain.EXIT_USAGE, SchemeMain.run(new String[]{"-I"}, new ByteArrayOutputStream(), err));
    }
}
