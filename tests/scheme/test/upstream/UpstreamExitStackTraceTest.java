package scheme.test.upstream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.Test;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.TruffleStackTrace;
import com.oracle.truffle.api.TruffleStackTraceElement;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * A missing feature of Truffle 25.6: a language can't add frames to a context exit's stack trace
 * (docs/upstream.md, "A context exit's stack trace is the Java stack's"). A guest exception records
 * each call target it leaves, so a language that keeps frames off the Java stack (a Scheme's
 * frames spilled to the heap) adds them by throwing it through their call targets
 * ({@code PromptDriver.unwind}). The exception {@code closeExited} throws isn't a guest exception:
 * its trace is the Java stack's, walked the first time it leaves a call target, and the call
 * targets it leaves after that add nothing. Our workaround: none; design.md D8 says an exit's trace
 * leaves out the pending frames, and {@code EmbeddingTest} asserts each mode's trace.
 *
 * <p>Inverted, like every test in this package: it passes while Truffle lacks the feature. When a
 * Truffle upgrade adds it, it fails; then give the exit the pending frames, assert them under torture
 * in {@code EmbeddingTest}, and remove this test and its entry in docs/upstream.md.
 */
public class UpstreamExitStackTraceTest {

    private static final LanguageReference<UpstreamLanguage> LANGUAGE = LanguageReference.create(UpstreamLanguage.class);
    private static final ContextReference<UpstreamLanguage.UpstreamContext> CONTEXT = ContextReference.create(UpstreamLanguage.class);

    @Test
    public void framesAnExitIsThrownThroughAreNotInItsTrace() {
        // Expected: an exit records rethrow, as a guest exception does. Actual: it doesn't.
        assertEquals(List.of("leaf", "rethrow", "middle"), framesOf(false));
        assertEquals(List.of("leaf", "middle"), framesOf(true));
    }

    /**
     * Calls {@code middle}, which calls {@code leaf}, which throws: an exit through
     * {@code closeExited}, or a guest exception. {@code middle} catches it and throws it through
     * {@code rethrow}, a root that throws its argument, as a language unwinds through frames that
     * wait on the heap, then throws it on. The frames of its stack trace, innermost first.
     */
    private static List<String> framesOf(boolean exit) {
        Context context = Context.create(UpstreamLanguage.ID);
        List<String> frames = new ArrayList<>();
        try {
            context.initialize(UpstreamLanguage.ID);
            context.enter();
            Throwable thrown;
            try {
                UpstreamLanguage language = LANGUAGE.get(null);
                RootCallTarget leaf = new Leaf(language, exit).getCallTarget();
                RootCallTarget rethrow = new Rethrow(language).getCallTarget();
                RootCallTarget middle = new Middle(language, leaf, rethrow).getCallTarget();
                try {
                    middle.call();
                    throw new AssertionError("middle returned");
                } catch (Throwable t) {
                    thrown = t;
                }
                for (TruffleStackTraceElement element : TruffleStackTrace.getStackTrace(thrown)) {
                    frames.add(element.getTarget().getRootNode().getName());
                }
                assertEquals(exit, !(thrown instanceof AbstractTruffleException));
            } finally {
                context.leave();
            }
        } finally {
            try {
                context.close();
                assertFalse("closing an exited context is an exit", exit);
            } catch (PolyglotException e) {
                assertTrue(e.toString(), exit && e.isExit());
            }
        }
        return frames;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }

    /** A guest exception. */
    @SuppressWarnings("serial")
    private static final class Raised extends AbstractTruffleException {
        Raised(Node location) {
            super("raised", location);
        }
    }

    /** Exits the context, or throws a guest exception. */
    private static final class Leaf extends RootNode {
        private final boolean exit;

        Leaf(UpstreamLanguage language, boolean exit) {
            super(language);
            this.exit = exit;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            if (exit) {
                CONTEXT.get(this).env().getContext().closeExited(this, 3);
                throw new AssertionError("closeExited returned");
            }
            throw new Raised(this);
        }

        @Override
        public String getName() {
            return "leaf";
        }
    }

    /** Throws its argument. */
    private static final class Rethrow extends RootNode {
        Rethrow(UpstreamLanguage language) {
            super(language);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            throw sneakyThrow((Throwable) frame.getArguments()[0]);
        }

        @Override
        public String getName() {
            return "rethrow";
        }
    }

    /** Calls leaf, and throws what it throws through rethrow, then on. */
    private static final class Middle extends RootNode {
        private final RootCallTarget leaf;
        private final RootCallTarget rethrow;

        Middle(UpstreamLanguage language, RootCallTarget leaf, RootCallTarget rethrow) {
            super(language);
            this.leaf = leaf;
            this.rethrow = rethrow;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            try {
                leaf.call(this);
            } catch (Throwable t) {
                try {
                    rethrow.call(this, t);
                } catch (Throwable again) {
                    if (again != t) {
                        throw sneakyThrow(again);
                    }
                }
                throw sneakyThrow(t);
            }
            throw new AssertionError("leaf returned");
        }

        @Override
        public String getName() {
            return "middle";
        }
    }
}
