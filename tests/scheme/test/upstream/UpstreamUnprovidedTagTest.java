package scheme.test.upstream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.Test;

import com.oracle.truffle.api.instrumentation.StandardTags;

/**
 * Truffle 25.6 bug: an instrument whose filter names a tag the language doesn't provide breaks the
 * Bytecode DSL roots it visits (docs/upstream.md, "Tags the language doesn't provide"). The
 * instrumentation handler passes every tag its bindings name to
 * {@code RootNode.prepareForInstrumentation}, and the Bytecode DSL's generated override adds each
 * one to a {@code BytecodeConfig}, whose encoder throws for a tag the language doesn't provide
 * ("Invalid tag specified"). So {@code Context.eval} fails, where a filter for a tag no node has
 * should match no node, as it does in an AST interpreter. Our workaround: the language provides the
 * tags the tools we know of name: {@code DebuggerTags.AlwaysHalt}, which the debugger names (as
 * {@link UpstreamLanguage} does), and the variable tags (design.md D13).
 *
 * <p>Inverted, like every test in this package: it passes while the bug is there. When a Truffle
 * upgrade fixes the bug, it fails; then remove this test, {@link UpstreamTagInstrument} and the
 * entry in docs/upstream.md.
 */
public class UpstreamUnprovidedTagTest {

    /** A statement, and an expression in it. */
    private static final String PROGRAM = "(x)";

    /** Runs the program with a listener for {@code tags}; how many nodes the listener entered. */
    private static int run(Class<?>... tags) {
        try (Context context = Context.create(UpstreamLanguage.ID)) {
            UpstreamTagInstrument instrument = context.getEngine().getInstruments().get(UpstreamTagInstrument.ID).lookup(UpstreamTagInstrument.class);
            instrument.attach(tags);
            assertEquals(42, context.eval(UpstreamLanguage.ID, PROGRAM).asInt());
            return instrument.entered.get();
        }
    }

    @Test
    public void aFilterForATagTheLanguageDoesntProvideFailsTheProgram() {
        // Expected: the program runs, and the listener enters nothing: no node has the tag.
        PolyglotException e = assertThrows(PolyglotException.class, () -> run(StandardTags.TryBlockTag.class));
        assertEquals(e.toString(), "java.lang.IllegalArgumentException: Invalid tag specified. Tag 'com.oracle.truffle.api.instrumentation.StandardTags$TryBlockTag' " +
                        "not provided by language 'scheme.test.upstream.UpstreamLanguage'.", e.getMessage());
    }

    @Test
    public void soDoesAFilterThatAlsoNamesATagItProvides() {
        // Expected: the program runs, and the listener enters the statement.
        assertThrows(PolyglotException.class, () -> run(StandardTags.StatementTag.class, StandardTags.TryBlockTag.class));
    }

    /** Control: with tags the language provides, the program runs and the listener enters them. */
    @Test
    public void aFilterForTagsItProvidesEntersTheirNodes() {
        assertEquals(1, run(StandardTags.StatementTag.class));
        assertEquals(2, run(StandardTags.StatementTag.class, StandardTags.ExpressionTag.class));
    }
}
