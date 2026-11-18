package scheme.test;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

import scheme.launcher.ArgumentFile;

/**
 * M11-03: the native launcher's {@code --vm.@file} (design.md D17, "Options"). The launcher checks
 * the options in the file as the thin launcher's C part reads them ({@code launcher.cc}'s
 * {@code arg_file_next_token}, a port of the JDK launcher's {@code libjli/args.c}), so each case
 * here is what that C part gives the isolate.
 */
public class ArgumentFileTest {

    private static List<String> tokens(String text) {
        return ArgumentFile.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void whiteSpaceSeparatesOptions() {
        assertEquals(List.of("-Xmx1g", "-Xss2m", "-Dx=y", "-ea"), tokens("-Xmx1g -Xss2m\n\t-Dx=y\r\n\f-ea"));
        assertEquals(List.of(), tokens(""));
        assertEquals(List.of(), tokens("  \n\n "));
    }

    @Test
    public void commentsRunToTheEndOfTheLine() {
        assertEquals(List.of("-Xmx1g", "-ea"), tokens("# heap\n-Xmx1g # trailing\n-ea"));
        assertEquals(List.of(), tokens("# only a comment"));
    }

    /** A comment inside an option ends at the line, and the option goes on after it, as in launcher.cc. */
    @Test
    public void aCommentInsideAnOptionJoinsTheNextLine() {
        assertEquals(List.of("-Dpolyglot.scheme.FooBar=1"), tokens("-Dpolyglot.scheme.Foo#comment\nBar=1\n"));
    }

    @Test
    public void quotesKeepWhiteSpaceAndTheOtherQuote() {
        assertEquals(List.of("-Dname=a b", "-Dq=\"x\"", "-Dh=#1"), tokens("\"-Dname=a b\" '-Dq=\"x\"' \"-Dh=#1\""));
        assertEquals(List.of("-Dpart=a b c"), tokens("-Dpart=\"a b\"' c'"));
        assertEquals(List.of(""), tokens("\"\" "));
    }

    @Test
    public void backslashesEscapeOnlyInQuotes() {
        assertEquals(List.of("-Dp=C:\\dir"), tokens("-Dp=C:\\dir"));
        assertEquals(List.of("-Dt=a\tb\nc\\d\"e"), tokens("\"-Dt=a\\tb\\nc\\\\d\\\"e\""));
    }

    @Test
    public void anEscapedNewLineInQuotesContinuesTheOption() {
        assertEquals(List.of("-Dpolyglot.scheme.Abcd=1"), tokens("\"-Dpolyglot.scheme.Ab\\\n   cd=1\""));
    }

    @Test
    public void aNewLineEndsAnOpenQuote() {
        assertEquals(List.of("-Dx=a", "-ea"), tokens("\"-Dx=a\n-ea"));
    }

    @Test
    public void bytesAreUtf8() {
        assertEquals(List.of("-Dname=\u00e9\u03bb"), tokens("-Dname=\u00e9\u03bb"));
    }
}
