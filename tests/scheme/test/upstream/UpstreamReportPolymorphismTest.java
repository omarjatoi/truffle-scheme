package scheme.test.upstream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * Truffle 25.5 bug: {@code @ReportPolymorphism} on a Bytecode DSL operation makes the DSL generate
 * code that doesn't compile: the operation node's {@code checkForPolymorphicSpecialize} reads
 * {@code $bc} and {@code $bci}, which aren't in scope there (docs/upstream.md,
 * "{@code @ReportPolymorphism} on a Bytecode DSL operation doesn't compile"). Our workaround:
 * {@code SchemeRootNode.Call} reports polymorphism itself, through {@code Trampoline.megamorphic}
 * (scheme.runtime).
 *
 * <p>The reproducer can't be part of the build, so this test runs javac, with the DSL processor
 * the test runner gives the tests ({@code -Dr7rs.truffle.dslProcessor}), on {@link #SOURCE}: once as it is,
 * which fails, and once without the annotation, which compiles.
 *
 * <p>Inverted, like every test in this package: it passes while the bug is there. When a Truffle
 * upgrade fixes the bug, it fails; then remove the workaround (put {@code @ReportPolymorphism} on
 * {@code Call}), this test and its entry in docs/upstream.md.
 */
public class UpstreamReportPolymorphismTest {

    /**
     * The reproducer, as in docs/upstream.md. (Its package line is outside the text block, so that
     * tools reading this file's package don't take it for this file's.)
     */
    static final String SOURCE = "package repro;\n\n" + """
                    import com.oracle.truffle.api.TruffleLanguage;
                    import com.oracle.truffle.api.bytecode.BytecodeRootNode;
                    import com.oracle.truffle.api.bytecode.GenerateBytecode;
                    import com.oracle.truffle.api.bytecode.Operation;
                    import com.oracle.truffle.api.dsl.ReportPolymorphism;
                    import com.oracle.truffle.api.dsl.Specialization;
                    import com.oracle.truffle.api.frame.FrameDescriptor;
                    import com.oracle.truffle.api.nodes.RootNode;

                    @GenerateBytecode(languageClass = PolyRoot.Lang.class)
                    public abstract class PolyRoot extends RootNode implements BytecodeRootNode {

                        protected PolyRoot(Lang language, FrameDescriptor frameDescriptor) {
                            super(language, frameDescriptor);
                        }

                        public abstract static class Lang extends TruffleLanguage<Object> {
                        }

                        @Operation
                        @ReportPolymorphism
                        public static final class Describe {
                            @Specialization
                            static String doInt(int value) {
                                return "int";
                            }

                            @Specialization
                            static String doString(String value) {
                                return "string";
                            }
                        }
                    }
                    """;

    private static final String ANNOTATION = "    @ReportPolymorphism\n";

    @Test
    public void reportPolymorphismOnAnOperationGeneratesCodeThatDoesNotCompile() throws IOException, InterruptedException {
        assertTrue(SOURCE.contains(ANNOTATION));
        Result without = javac(SOURCE.replace(ANNOTATION, ""));
        assertEquals("without @ReportPolymorphism:\n" + without.output, 0, without.status);

        Result with = javac(SOURCE);
        assertEquals("with @ReportPolymorphism:\n" + with.output, 1, with.status);
        // Expected: it compiles, like the source without the annotation. Actual: in the generated
        // operation node, checkForPolymorphicSpecialize reads $bc and $bci, which it doesn't have.
        assertTrue(with.output, with.output.contains("PolyRootGen.java"));
        assertTrue(with.output, with.output.contains("error: cannot find symbol"));
        assertTrue(with.output, with.output.contains("variable $bc\n"));
        assertTrue(with.output, with.output.contains("variable $bci\n"));
        assertTrue(with.output, with.output.contains("2 errors"));
    }

    private record Result(int status, String output) {
    }

    /** Compiles {@code source} as repro/PolyRoot.java with the Truffle DSL processor. */
    private static Result javac(String source) throws IOException, InterruptedException {
        String processor = System.getProperty("r7rs.truffle.dslProcessor");
        assertNotNull("the test runner sets -Dr7rs.truffle.dslProcessor (bazel/jvm_test.bzl)", processor);
        // Truffle's jars are on the test JVM's module path; javac only needs them as a class path.
        String classPath = String.join(File.pathSeparator, Stream.of(System.getProperty("jdk.module.path"), System.getProperty("java.class.path")) //
                        .filter(p -> p != null && !p.isEmpty()).toList());
        Path dir = Files.createTempDirectory("upstream-repro");
        try {
            Path file = dir.resolve("src").resolve("repro").resolve("PolyRoot.java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source, StandardCharsets.UTF_8);
            Path javac = Path.of(System.getProperty("java.home"), "bin", "javac");
            ProcessBuilder builder = new ProcessBuilder(List.of(javac.toString(), "-nowarn", "-cp", classPath, "-processorpath", processor,
                            "-d", dir.resolve("out").toString(), file.toString()));
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new Result(process.waitFor(), output);
        } finally {
            try (Stream<Path> paths = Files.walk(dir)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
    }
}
