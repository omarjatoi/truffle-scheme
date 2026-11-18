package scheme.test.upstream;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.graalvm.polyglot.Context;
import org.junit.Test;

import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.source.Source;

/**
 * Truffle 25.5 tools' bug: the CPU tracer and the coverage tool count a frame that yields and is
 * resumed as two entries (docs/upstream.md, "Tools take a resumption for an entry"). Their nodes
 * override {@code onEnter} only, and {@code ExecutionEventNode.onResume} calls {@code onEnter}. In
 * Scheme, a recursion that spills to the heap (design.md "Stack-depth spill") counts each spilled
 * frame once more; there's no workaround. docs/usage.md's tools table says so.
 *
 * <p>Runs the tools through their options, as the launcher does (the coverage tool doesn't export
 * its API). Inverted, like every test in this package: it passes while the bug is there. When a
 * GraalVM upgrade fixes it, it fails; then remove this test, its entry in docs/upstream.md, and the
 * limitation in docs/usage.md and design.md D13 ("Yields and tools").
 */
public class UpstreamResumedEntriesTest {

    private static final LanguageReference<UpstreamLanguage> LANGUAGE = LanguageReference.create(UpstreamLanguage.class);

    private static final Pattern COUNT = Pattern.compile("\"count\"\\s*:\\s*(\\d+)");

    /**
     * Runs one root that yields once and is resumed once, with the tool's {@code options}, and
     * returns every count in the tool's JSON output, which it writes to {@code output} as the
     * context closes.
     */
    private static List<Long> counts(Path output, String... options) throws IOException {
        Context.Builder builder = Context.newBuilder(UpstreamLanguage.ID);
        for (int i = 0; i < options.length; i += 2) {
            builder.option(options[i], options[i + 1]);
        }
        try (Context context = builder.build()) {
            context.initialize(UpstreamLanguage.ID);
            context.enter();
            try {
                Source source = Source.newBuilder(UpstreamLanguage.ID, "return yield 1", "resumed.txt").build();
                UpstreamTaggedYieldRoot root = UpstreamTaggedYieldRoot.create(LANGUAGE.get(null), source);
                ContinuationResult yielded = (ContinuationResult) root.getCallTarget().call();
                assertEquals(2, yielded.continueWith(2));
            } finally {
                context.leave();
            }
        }
        List<Long> counts = new ArrayList<>();
        Matcher m = COUNT.matcher(Files.readString(output, StandardCharsets.UTF_8));
        while (m.find()) {
            counts.add(Long.parseLong(m.group(1)));
        }
        return counts;
    }

    @Test
    public void theCpuTracerCountsAResumptionAsAnEntry() throws IOException {
        Path output = Files.createTempFile("cputracer", ".json");
        try {
            // The root, entered once. Expected: [1]. Actual: [2].
            assertEquals(List.of(2L), counts(output, "cputracer", "true", "cputracer.Output", "json", "cputracer.OutputFile", output.toString()));
        } finally {
            Files.deleteIfExists(output);
        }
    }

    @Test
    public void theCoverageToolCountsAResumptionAsAnEntry() throws IOException {
        Path output = Files.createTempFile("coverage", ".json");
        try {
            // The root and its statement, each entered once. Expected: [1, 1]. Actual: [2, 2].
            assertEquals(List.of(2L, 2L), counts(output, "coverage", "true", "coverage.Count", "true", "coverage.Output", "json", "coverage.OutputFile", output.toString()));
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
