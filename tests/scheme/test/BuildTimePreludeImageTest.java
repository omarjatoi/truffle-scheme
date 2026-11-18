package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import scheme.compiler.PreludeImageCheck;

/**
 * M7-05: the prelude image is built at build time and bundled with the language (design.md D13),
 * so the first context of a process restores the standard libraries instead of expanding them. An
 * image that is out of date with the prelude's files, or damaged, is detected and not used.
 */
public class BuildTimePreludeImageTest {

    private static final String SCHEME = "scheme";

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static byte[] bundled() {
        byte[] image = PreludeImageCheck.bundled();
        assertNotNull("the SCHEME jar has a prelude image (the build makes it)", image);
        return image;
    }

    /** {@code image} with its checksum (the last 8 bytes: a CRC32 of the rest) made right again. */
    private static byte[] withChecksum(byte[] image) {
        CRC32 crc = new CRC32();
        crc.update(image, 0, image.length - Long.BYTES);
        ByteBuffer.wrap(image, image.length - Long.BYTES, Long.BYTES).putLong(crc.getValue());
        return image;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            if (Arrays.equals(haystack, i, i + needle.length, needle, 0, needle.length)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The bundled image as if it had been built from another numbers.scm: the copy of that file it
     * records differs in one byte, and its checksum is right. What a stale image looks like.
     */
    private static byte[] staleImage() {
        byte[] image = bundled().clone();
        byte[] numbers = PreludeImageCheck.preludeFile("numbers.scm");
        int at = indexOf(image, numbers);
        assertTrue("the image records numbers.scm", at >= 0);
        image[at + numbers.length / 2] ^= 1;
        return withChecksum(image);
    }

    @Test
    public void bundledImageIsUpToDate() {
        assertNull(PreludeImageCheck.staleness(bundled(), PreludeImageCheck::preludeFile));
    }

    @Test
    public void aChangedPreludeFileMakesItStale() {
        byte[] edited = (new String(PreludeImageCheck.preludeFile("numbers.scm"), StandardCharsets.UTF_8) + "\n(define x 1)\n").getBytes(StandardCharsets.UTF_8);
        assertEquals("prelude/numbers.scm changed", PreludeImageCheck.staleness(bundled(), name -> name.equals("numbers.scm") ? edited : PreludeImageCheck.preludeFile(name)));
        assertEquals("prelude/numbers.scm changed", PreludeImageCheck.staleness(staleImage(), PreludeImageCheck::preludeFile));
    }

    @Test
    public void aRemovedPreludeFileMakesItStale() {
        assertEquals("prelude/control.scm was removed", PreludeImageCheck.staleness(bundled(), name -> name.equals("control.scm") ? null : PreludeImageCheck.preludeFile(name)));
    }

    @Test
    public void anyDamageIsDetected() {
        byte[] image = bundled();
        for (int at : new int[]{0, 5, 100, image.length / 3, image.length / 2, image.length - 9, image.length - 1}) {
            byte[] damaged = image.clone();
            damaged[at] ^= 0x40;
            assertEquals("byte " + at, "its checksum doesn't match", PreludeImageCheck.staleness(damaged, PreludeImageCheck::preludeFile));
        }
        assertNotNull(PreludeImageCheck.staleness(Arrays.copyOf(image, image.length / 2), PreludeImageCheck::preludeFile));
        assertNotNull(PreludeImageCheck.staleness(new byte[3], PreludeImageCheck::preludeFile));
        // Damage with a right checksum: an image whose records are cut short.
        byte[] cut = new byte[image.length - 1000];
        System.arraycopy(image, 0, cut, 0, cut.length - Long.BYTES);
        assertNotNull(PreludeImageCheck.staleness(withChecksum(cut), PreludeImageCheck::preludeFile));
    }

    // --- in a context ------------------------------------------------------------------------

    private record Result(int status, String out, String err) {
    }

    /** Runs the launcher in a child JVM with this one's module path, as LauncherTest does. */
    private Result launch(List<String> jvmArgs, String... args) throws IOException, InterruptedException {
        String java = ProcessHandle.current().info().command().orElseThrow();
        String modulePath = System.getProperty("jdk.module.path");
        assertNotNull("the tests run on the module path", modulePath);
        List<String> command = new ArrayList<>(List.of(java, "--module-path", modulePath, "--enable-native-access=org.graalvm.truffle", "--sun-misc-unsafe-memory-access=allow"));
        command.addAll(jvmArgs);
        command.addAll(List.of("-m", "scheme.launcher/scheme.launcher.SchemeMain", "--log.scheme.prelude.level=FINE"));
        command.addAll(List.of(args));
        Path out = folder.newFile().toPath();
        Path err = folder.newFile().toPath();
        Process process = new ProcessBuilder(command).directory(folder.getRoot()).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("scheme " + String.join(" ", args) + " timed out");
        }
        return new Result(process.exitValue(), Files.readString(out, StandardCharsets.UTF_8), Files.readString(err, StandardCharsets.UTF_8));
    }

    private Path program() throws IOException {
        Path program = folder.getRoot().toPath().resolve("prog.scm");
        Files.writeString(program, """
                        (import (scheme base) (scheme write) (scheme case-lambda))
                        (define-record-type point (make-point x y) point? (x point-x) (y point-y))
                        (define f (case-lambda ((a) a) ((a b) (+ a b))))
                        (write (list (f 1 2) (point-x (make-point 3 4)) (inexact 1/3) (guard (e (#t 'caught)) (car 1))))
                        """, StandardCharsets.UTF_8);
        return program;
    }

    private static final String PROGRAM_OUTPUT = "(3 3 0.3333333333333333 caught)";

    @Test
    public void theFirstContextRestoresFromIt() throws IOException, InterruptedException {
        Result r = launch(List.of(), program().toString());
        assertEquals(r.err(), 0, r.status());
        assertEquals(PROGRAM_OUTPUT, r.out());
        assertTrue(r.err(), r.err().contains("the standard libraries are restored from the build-time prelude image"));
    }

    @Test
    public void aStaleImageIsNotUsed() throws IOException, InterruptedException {
        Path stale = folder.getRoot().toPath().resolve("stale.image");
        Files.write(stale, staleImage());
        Result r = launch(List.of("-Dscheme.preludeImage.file=" + stale), program().toString());
        assertEquals(r.err(), 0, r.status());
        assertEquals(PROGRAM_OUTPUT, r.out());
        assertTrue(r.err(), r.err().contains("the build-time prelude image isn't used: prelude/numbers.scm changed; the standard libraries are expanded"));
        assertTrue(r.err(), !r.err().contains("restored from"));
    }

    @Test
    public void aDamagedImageIsNotUsed() throws IOException, InterruptedException {
        Path damaged = folder.getRoot().toPath().resolve("damaged.image");
        byte[] image = bundled().clone();
        image[image.length / 2] ^= 1;
        Files.write(damaged, image);
        Result r = launch(List.of("-Dscheme.preludeImage.file=" + damaged), program().toString());
        assertEquals(r.err(), 0, r.status());
        assertEquals(PROGRAM_OUTPUT, r.out());
        assertTrue(r.err(), r.err().contains("the build-time prelude image isn't used: its checksum doesn't match"));
    }

    /** Every variable of the interaction environment, and what {@code write} shows of its value. */
    private static TreeMap<String, String> interactionVariables(Context context) {
        TreeMap<String, String> variables = new TreeMap<>();
        Value bindings = context.getBindings(SCHEME);
        Value write = context.eval(SCHEME, "(lambda (x) (let ((p (open-output-string))) (write x p) (get-output-string p)))");
        for (String name : bindings.getMemberKeys()) {
            variables.put(name, write.execute(bindings.getMember(name)).asString());
        }
        return variables;
    }

    @Test
    public void restoringIsExpandingAhead() {
        String program = """
                        (define-record-type point (make-point x y) point? (x point-x) (y point-y set-point-y!))
                        (define-values (q r) (floor/ 7 2))
                        (define p (make-parameter 1))
                        (define s (make-string 2 #\\a))
                        (string-set! s 0 #\\b)
                        (list (let-values (((a b) (values 1 2))) (list b a)) q r (parameterize ((p 10)) (p)) s
                              (guard (e ((error-object? e) (error-object-message e))) (error "boom" 1))
                              (case 2 ((1) 'one) ((2) 'two)) (do ((i 0 (+ i 1)) (s 0 (+ s i))) ((= i 4) s))
                              (force (delay-force (delay 'done))) (let ((x (make-point 1 2))) (set-point-y! x 5) (point-y x))
                              (vector-map + #(1 2) #(10 20)) (string->list "abc") (exact (floor 2.5)) (char-upcase #\\a)
                              (call-with-current-continuation (lambda (k) (dynamic-wind (lambda () #f) (lambda () (k 'escaped)) (lambda () #f)))))
                        """;
        String expanded;
        TreeMap<String, String> expandedVariables;
        try (Context context = Context.newBuilder(SCHEME).option("scheme.PreludeImage", "false").build()) {
            expanded = context.eval(SCHEME, program).toString();
            expandedVariables = interactionVariables(context);
        }
        try (Context context = Context.create(SCHEME)) {
            assertEquals(expanded, context.eval(SCHEME, program).toString());
            assertEquals(expandedVariables, interactionVariables(context));
        }
        assertTrue(expandedVariables.toString(), expandedVariables.size() > 300);
    }
}
