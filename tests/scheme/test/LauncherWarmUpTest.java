package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.graalvm.polyglot.Context;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import scheme.launcher.SchemeMain;

/**
 * M8-03: the launcher warms the JIT up while it runs a program, on a thread and in a context of its
 * own (design.md D17, {@link SchemeMain#WARM_UP}). The warm-up gets its procedure compiled, and a
 * program run through the real launcher, which starts it, writes what it writes and nothing more.
 */
public class LauncherWarmUpTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void warmUpCompilesItsProcedure() {
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        Context.Builder builder = Context.newBuilder("scheme").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.TraceCompilation", "true").logHandler(log);
        SchemeMain.warmUp(builder);
        String trace = log.toString(StandardCharsets.UTF_8);
        assertTrue(trace, trace.matches("(?s).*opt done .*%launcher-warm-up .*"));
    }

    @Test
    public void programWritesOnlyItsOwnOutput() throws IOException, InterruptedException {
        Path program = folder.getRoot().toPath().resolve("hello.scm");
        Files.writeString(program, "(import (scheme base) (scheme write))\n(display \"hello\")\n", StandardCharsets.UTF_8);
        String java = ProcessHandle.current().info().command().orElseThrow();
        String modulePath = System.getProperty("jdk.module.path");
        assertNotNull("the tests run on the module path", modulePath);
        Path out = folder.newFile().toPath();
        Path err = folder.newFile().toPath();
        Process process = new ProcessBuilder(List.of(java, "--module-path", modulePath, "--enable-native-access=org.graalvm.truffle", "--sun-misc-unsafe-memory-access=allow", "-m",
                        "scheme.launcher/scheme.launcher.SchemeMain", program.toString())).directory(folder.getRoot()).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        process.getOutputStream().close();
        assertTrue("the JVM doesn't wait for the warm-up", process.waitFor(120, TimeUnit.SECONDS));
        assertEquals(Files.readString(err, StandardCharsets.UTF_8), 0, process.exitValue());
        assertEquals("hello", Files.readString(out, StandardCharsets.UTF_8));
        assertEquals("", Files.readString(err, StandardCharsets.UTF_8));
    }
}
