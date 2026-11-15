package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import scheme.reader.Reader;
import scheme.runtime.SchemeError;
import scheme.runtime.printer.Printer;
import scheme.runtime.printer.Printer.Mode;
import scheme.runtime.value.SChar;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SErrorObject;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SString;
import scheme.runtime.value.SSymbol;
import scheme.runtime.value.SymbolTable;

/**
 * M1-04: runs the vendored suite's reader cases (tests/r7rs/6.13.scm, "Read syntax" and "Numeric
 * syntax") directly against the Java reader and printer. The suite file itself is parsed with our
 * reader, so it is also a test that the reader handles a real source file.
 */
public class SuiteReadSyntaxTest {

    private final SymbolTable symbols = new SymbolTable();

    static String resource(String path) throws IOException {
        try (InputStream in = SuiteReadSyntaxTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("missing test resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private List<Object> readAll(String text) {
        Reader reader = Reader.forString(text, symbols, true);
        List<Object> data = new ArrayList<>();
        for (Object d = reader.read(); d != SConstant.EOF; d = reader.read()) {
            data.add(d);
        }
        return data;
    }

    private Object readOne(String text) {
        return Reader.forString(text, symbols, false).read();
    }

    private static List<Object> toList(Object list) {
        List<Object> items = new ArrayList<>();
        Object rest = list;
        while (rest instanceof SPair p) {
            items.add(p.car);
            rest = p.cdr;
        }
        return items;
    }

    private boolean isCall(Object form, String head) {
        return form instanceof SPair p && p.car == symbols.intern(head);
    }

    private static String write(Object o) {
        return Printer.print(o, Mode.WRITE);
    }

    private static String str(Object o) {
        return ((SString) o).toString();
    }

    @Test
    public void suiteReaderCases() throws IOException {
        int numeric = 0;
        int precision = 0;
        int reads = 0;
        int readErrors = 0;
        for (Object form : readAll(resource("/r7rs/6.13.scm"))) {
            List<Object> f = toList(form);
            if (isCall(form, "test-numeric-syntax")) {
                checkNumericSyntax(f);
                numeric++;
            } else if (isCall(form, "test-precision")) {
                checkPrecision(f);
                precision++;
            } else if (isCall(form, "test-read-error")) {
                String input = str(f.get(1));
                try {
                    Object value = readOne(input);
                    fail("expected a read error for " + input + ", read " + write(value));
                } catch (SchemeError e) {
                    assertEquals(input, SErrorObject.Kind.READ, e.getErrorObject().kind);
                }
                readErrors++;
            } else if (isCall(form, "test") && f.size() == 3 && checkReadTest(f.get(1), f.get(2))) {
                reads++;
            }
        }
        // Guard against silently matching nothing.
        assertTrue("numeric cases: " + numeric, numeric >= 95);
        assertTrue("precision cases: " + precision, precision >= 10);
        assertTrue("read cases: " + reads, reads >= 60);
        assertEquals(6, readErrors);
    }

    /** (test-numeric-syntax str expect alt ...): writing what str reads must give str or an alt. */
    private void checkNumericSyntax(List<Object> f) {
        String input = str(f.get(1));
        Object z = readOne(input);
        String written = write(z);
        List<String> accepted = new ArrayList<>();
        accepted.add(input);
        for (Object alt : f.subList(3, f.size())) {
            accepted.add(str(alt));
        }
        assertTrue("(test-numeric-syntax " + input + "): wrote " + written + ", accepted " + accepted, accepted.contains(written));
        Object expect = f.get(2);
        if (!(expect instanceof SPair) && !(expect instanceof SSymbol)) {
            assertEquals("(test-numeric-syntax " + input + ") value", write(expect), written);
            assertEquals("(test-numeric-syntax " + input + ") exactness", expect.getClass(), z.getClass());
        }
    }

    /** (test-precision str alt ...): number->string of what str reads must be str or an alt, and read back eqv. */
    private void checkPrecision(List<Object> f) {
        String input = str(f.get(1));
        Object n = readOne(input);
        String written = write(n);
        List<String> accepted = new ArrayList<>();
        for (Object s : f.subList(1, f.size())) {
            accepted.add(str(s));
        }
        assertTrue("(test-precision " + input + "): wrote " + written + ", accepted " + accepted, accepted.contains(written));
        assertEquals("(test-precision " + input + ") round trip", written, write(readOne(written)));
    }

    /**
     * (test expect (read (open-input-string s))) and its variants with read2, cadr, char->integer
     * and string-ref. Returns false if the form is some other kind of test.
     */
    private boolean checkReadTest(Object expectForm, Object expr) {
        Object expected = isCall(expectForm, "quote") ? ((SPair) ((SPair) expectForm).cdr).car : expectForm;
        if (isCall(expr, "read") || isCall(expr, "read2")) {
            Object port = toList(expr).get(1);
            if (!isCall(port, "open-input-string")) {
                return false;
            }
            String input = str(toList(port).get(1));
            Object actual;
            if (isCall(expr, "read")) {
                actual = readOne(input);
            } else {
                Reader r = Reader.forString(input, symbols, false);
                Object o1 = r.read();
                actual = new SPair(o1, r.read());
            }
            assertEquals("read of " + input, write(expected), write(actual));
            return true;
        }
        if (isCall(expr, "cadr") && isCall(toList(expr).get(1), "read")) {
            String input = str(toList(toList(toList(expr).get(1)).get(1)).get(1));
            Object actual = ((SPair) ((SPair) readOne(input)).cdr).car;
            assertEquals("cadr of read of " + input, write(expected), write(actual));
            return true;
        }
        if (isCall(expr, "char->integer")) {
            Object arg = toList(expr).get(1);
            if (isCall(arg, "read")) {
                String input = str(toList(toList(arg).get(1)).get(1));
                assertEquals("char read from " + input, ((Long) expected).intValue(), ((SChar) readOne(input)).codePoint);
                return true;
            }
            if (isCall(arg, "string-ref") && isCall(toList(arg).get(1), "read")) {
                String input = str(toList(toList(toList(arg).get(1)).get(1)).get(1));
                assertEquals("string read from " + input, ((Long) expected).intValue(), ((SString) readOne(input)).codePointAt(0));
                return true;
            }
        }
        return false;
    }

    /** Every vendored suite file reads to the end without a read error. */
    @Test
    public void everySuiteFileReads() throws IOException {
        String[] sections = resource("/r7rs/sections.tsv").lines().filter(l -> !l.startsWith("#") && !l.isBlank()).map(l -> l.split("\t")[0]).toArray(String[]::new);
        assertEquals(18, sections.length);
        for (String section : sections) {
            List<Object> data = readAll(resource("/r7rs/" + section + ".scm"));
            assertNotEquals(section, 0, data.size());
            assertTrue(section, isCall(data.get(0), "import"));
        }
    }
}
