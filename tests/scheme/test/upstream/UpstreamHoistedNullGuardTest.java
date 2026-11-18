package scheme.test.upstream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.graalvm.polyglot.Context;
import org.junit.Assume;
import org.junit.Test;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * Graal 25.5 bug: speculative guard movement moves a guard that can only fail out of the loop it
 * is in, so the compiled code deoptimizes, and is invalidated, the first time it reaches the loop
 * (docs/upstream.md, "A guard that can only fail is moved out of its loop"). The guard is the null
 * check of a field read after a cast that no profile says is never null: Graal compiles the cast
 * into a branch for null, and the read's null check on that branch has the constant
 * {@code true} for its condition. The branch never runs, but the phase takes the constant for
 * loop-invariant and moves the guard to before the loop. The speculation log then keeps the
 * recompilation from moving it, so each call target pays once. Our workaround: the operations that
 * read a box or a closure's captured value check their operand's type ({@code instanceof}) instead
 * of casting it ({@code SchemeRootNode.BoxRef}, scheme.runtime; design.md D17).
 *
 * <p>Inverted, like every test in this package: it passes while the bug is there. When a Graal
 * upgrade fixes the bug, it fails; then the operations may take their operand's type again, and
 * this test and its entry in docs/upstream.md go.
 */
public class UpstreamHoistedNullGuardTest {

    private static final LanguageReference<UpstreamLanguage> LANGUAGE = LanguageReference.create(UpstreamLanguage.class);

    private static final Pattern INVALIDATED = Pattern.compile("opt deopt\\s+engine=\\d+\\s+id=\\d+\\s+(\\S+)\\s+\\|Invalidated\\s+true\\|");

    /** A mutable cell, like a Scheme box. */
    static final class Cell {
        Object value;

        Cell(Object value) {
            this.value = value;
        }
    }

    /**
     * Reads {@code cells[0]}'s value {@code n} times and counts the reads that found a value: a
     * loop whose body casts an array element, never null, and reads a field of it. With
     * {@code checked}, the body tests the element's type instead of casting it.
     */
    static final class CellLoopRoot extends RootNode {
        private final boolean checked;
        private final String name;

        CellLoopRoot(TruffleLanguage<?> language, boolean checked) {
            super(language);
            this.checked = checked;
            this.name = checked ? "checked-cell-loop" : "cast-cell-loop";
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object[] cells = (Object[]) frame.getArguments()[0];
            int n = (Integer) frame.getArguments()[1];
            int found = 0;
            for (int i = 0; i < n; i++) {
                Object cell = cells[0];
                Object value;
                if (checked) {
                    value = cell instanceof Cell c ? c.value : null;
                } else {
                    value = ((Cell) cell).value;
                }
                if (value != null) {
                    found++;
                }
            }
            return found;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** The names of the compilations the engine's log says were invalidated, one per line. */
    private static String invalidatedRunning(boolean checked) throws IOException {
        Path log = Files.createTempFile("upstream-hoisted-null-guard", ".log");
        try {
            Context.Builder builder = Context.newBuilder(UpstreamLanguage.ID).allowExperimentalOptions(true).option("log.file", log.toString()).out(new ByteArrayOutputStream());
            builder.option("engine.Compilation", "true").option("engine.CompileImmediately", "false").option("engine.BackgroundCompilation", "false");
            builder.option("engine.MultiTier", "false").option("engine.SingleTierCompilationThreshold", "100");
            builder.option("engine.TraceCompilation", "true");
            Context context;
            try {
                context = builder.build();
            } catch (IllegalArgumentException e) {
                Assume.assumeNoException("no optimizing Truffle runtime", e);
                throw e;
            }
            try (context) {
                context.initialize(UpstreamLanguage.ID);
                context.enter();
                try {
                    CallTarget target = new CellLoopRoot(LANGUAGE.get(null), checked).getCallTarget();
                    Object[] cells = {new Cell("value")};
                    for (int i = 0; i < 1000; i++) {
                        assertEquals(10, target.call(cells, 10));
                    }
                } finally {
                    context.leave();
                }
            }
            String text = Files.readString(log);
            assertTrue(text, text.contains("opt done"));
            StringBuilder names = new StringBuilder();
            Matcher m = INVALIDATED.matcher(text);
            while (m.find()) {
                names.append(m.group(1)).append('\n');
            }
            return names.toString();
        } finally {
            Files.delete(log);
        }
    }

    @Test
    public void aGuardThatCanOnlyFailIsMovedOutOfItsLoop() throws IOException {
        // Expected: the compiled loop runs without deoptimizing. Actual: it is invalidated the
        // first time it runs, by the null check of the cast's branch for null, moved before the
        // loop.
        assertEquals("cast-cell-loop\n", invalidatedRunning(false));
    }

    @Test
    public void theLoopIsNotInvalidatedWhenItTestsTheElementsType() throws IOException {
        assertEquals("", invalidatedRunning(true));
    }
}
