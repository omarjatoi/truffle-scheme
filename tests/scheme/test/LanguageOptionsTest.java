package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.graalvm.options.OptionDescriptor;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The language's options as GraalVM recommends declaring them (docs/graalvm-configuration.md,
 * "Language options"), and every way an option reaches a context: the context's builder, its
 * engine's builder, and a {@code polyglot.scheme.*} system property.
 */
public class LanguageOptionsTest {

    private static final String SCHEME = "scheme";
    private static final Pattern DEFAULT_AT_END = Pattern.compile(".*\\(default: [^()]+\\)\\.$");

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void everyOptionEndsItsHelpWithItsDefaultAndHasAUsageSyntax() {
        // @Option's Javadoc: "Include the default value for the option and the end of the text",
        // and usageSyntax "is used when generating help messages".
        List<String> names = new ArrayList<>();
        try (Engine engine = Engine.create(SCHEME)) {
            for (OptionDescriptor descriptor : engine.getLanguages().get(SCHEME).getOptions()) {
                names.add(descriptor.getName());
                assertTrue(descriptor.getName() + ": " + descriptor.getHelp(), DEFAULT_AT_END.matcher(descriptor.getHelp()).matches());
                assertFalse(descriptor.getName(), descriptor.getUsageSyntax() == null || descriptor.getUsageSyntax().isEmpty());
            }
        }
        assertTrue(names.toString(), names.containsAll(List.of("scheme.LibraryPath", "scheme.BufferOutput", "scheme.StackSize", "scheme.PreludeImage", "scheme.SpillDepth")));
    }

    @Test
    public void aBadValueFailsTheBuildAndNamesTheOption() {
        String[][] bad = {
                        {"scheme.StackSize", "lots"},
                        {"scheme.StackSize", "-1m"},
                        {"scheme.SpillDepth", "-5"},
                        {"scheme.SpillDepth", "deep"},
                        {"scheme.BufferOutput", "maybe"},
                        {"scheme.PreludeImage", "nope"},
                        {"scheme.NoSuchOption", "1"},
        };
        for (String[] option : bad) {
            Context.Builder builder = Context.newBuilder(SCHEME).allowExperimentalOptions(true).option(option[0], option[1]);
            IllegalArgumentException e = assertThrows(option[0] + "=" + option[1], IllegalArgumentException.class, builder::build);
            assertTrue(e.getMessage(), e.getMessage().contains(option[0]) || e.getMessage().contains("'" + option[1] + "'"));
        }
    }

    @Test
    public void anExperimentalOptionNeedsToBeAllowed() {
        // The compile mode's JVM allows every experimental option (its engine options are).
        Assume.assumeFalse(Boolean.getBoolean("polyglot.engine.AllowExperimentalOptions"));
        Context.Builder builder = Context.newBuilder(SCHEME).option("scheme.SpillDepth", "10");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(e.getMessage(), e.getMessage().contains("experimental"));
        try (Context context = Context.newBuilder(SCHEME).allowExperimentalOptions(true).option("scheme.SpillDepth", "10").build()) {
            assertEquals(3, context.eval(SCHEME, "(+ 1 2)").asInt());
        }
    }

    /** A library {@code (config lib)} in a directory of its own, whose {@code where} is {@code name}. */
    private String library(String name) throws IOException {
        Path dir = folder.newFolder(name).toPath();
        Path file = dir.resolve("config/lib.sld");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "(define-library (config lib) (import (scheme base)) (export where) (begin (define where \"" + name + "\")))", StandardCharsets.UTF_8);
        return dir.toString();
    }

    /** Runs a program that imports {@code (config lib)} in a context of {@code builder}'s: what it wrote. */
    private static String run(Context.Builder builder) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = builder.allowIO(IOAccess.ALL).out(out).build()) {
            context.eval(SCHEME, "(import (scheme base) (scheme write) (config lib)) (display where)");
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void aContextTakesAnOptionFromItsBuilder() throws IOException {
        assertEquals("context", run(Context.newBuilder(SCHEME).option("scheme.LibraryPath", library("context"))));
    }

    @Test
    public void aContextTakesAnOptionFromItsEngine() throws IOException {
        // An option given to an explicit engine is every one of its contexts' default.
        try (Engine engine = Engine.newBuilder(SCHEME).option("scheme.LibraryPath", library("engine")).build()) {
            assertEquals("engine", run(Context.newBuilder(SCHEME).engine(engine)));
            assertEquals("engine", run(Context.newBuilder(SCHEME).engine(engine)));
            // A context's own value comes first.
            assertEquals("context", run(Context.newBuilder(SCHEME).engine(engine).option("scheme.LibraryPath", library("context"))));
        }
    }

    @Test
    public void aContextTakesAnOptionFromASystemProperty() throws IOException {
        // An engine reads polyglot.<option> system properties when it is created
        // (Engine.Builder.useSystemProperties, true by default).
        String property = "polyglot.scheme.LibraryPath";
        System.setProperty(property, library("property"));
        try {
            assertEquals("property", run(Context.newBuilder(SCHEME)));
            assertEquals("context", run(Context.newBuilder(SCHEME).option("scheme.LibraryPath", library("context"))));
        } finally {
            System.clearProperty(property);
        }
    }
}
