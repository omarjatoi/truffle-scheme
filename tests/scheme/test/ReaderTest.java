package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import scheme.reader.Reader;
import scheme.runtime.SchemeError;
import scheme.runtime.printer.Printer;
import scheme.runtime.printer.Printer.Mode;
import scheme.runtime.value.SBigInteger;
import scheme.runtime.value.SBytevector;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SErrorObject;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SRational;
import scheme.runtime.value.SString;
import scheme.runtime.value.SVector;
import scheme.runtime.value.SymbolTable;

/** M1-04: reader cases beyond the suite's own (tests/r7rs/6.13.scm, see SuiteReadSyntaxTest). */
public class ReaderTest {

    private final SymbolTable symbols = new SymbolTable();

    private Object read(String text) {
        return Reader.forString(text, symbols, false).read();
    }

    private String roundTrip(String text) {
        return Printer.print(read(text), Mode.WRITE);
    }

    private SchemeError readError(String text) {
        SchemeError e = assertThrows(SchemeError.class, () -> read(text));
        assertEquals(text, SErrorObject.Kind.READ, e.getErrorObject().kind);
        return e;
    }

    @Test
    public void numbers() {
        assertEquals("123456789012345678901234567890", roundTrip("123456789012345678901234567890"));
        assertTrue(read("123456789012345678901234567890") instanceof SBigInteger);
        assertEquals(Long.MIN_VALUE, read("-9223372036854775808"));
        assertEquals("3/2500", roundTrip("#e1.2e-3"));
        assertTrue(read("#e1.2e-3") instanceof SRational);
        assertEquals("1000", roundTrip("#e1e3"));
        assertEquals("0.75", roundTrip("#i3/4"));
        assertEquals("-255", roundTrip("#x-FF"));
        assertEquals("1+2i", roundTrip("#e1+2i"));
        assertEquals("1.5-2.5i", roundTrip("1.5-2.5i"));
        assertEquals("0+i", roundTrip("+i"));
        assertEquals("1.0e+21", roundTrip("1e21"));
        assertEquals("2", roundTrip("2@0"));
        assertEquals("+inf.0", roundTrip("+inf.0"));
        assertEquals("-inf.0", roundTrip("#i-inf.0"));
        readError("1/0");
        readError("#e+inf.0");
        readError("1+");
        readError("#x1.5");
        readError("#e#e1");
        readError("1.2.3");
    }

    @Test
    public void symbolsAndFoldCase() {
        assertEquals("abc", roundTrip("abc"));
        assertEquals("+", roundTrip("+"));
        assertEquals("...", roundTrip("..."));
        assertEquals("->x", roundTrip("->x"));
        assertEquals("|a b|", roundTrip("|a b|"));
        assertEquals("|λ|", roundTrip("|\\x3bb;|"));
        assertSame(read("abc"), read("|abc|"));
        Reader r = Reader.forString("ABC #!fold-case ABC #\\NEWLINE |ABC| #!no-fold-case ABC", symbols, false);
        assertEquals("ABC", Printer.print(r.read(), Mode.WRITE));
        assertEquals("abc", Printer.print(r.read(), Mode.WRITE));
        assertEquals("#\\newline", Printer.print(r.read(), Mode.WRITE));
        assertEquals("ABC", Printer.print(r.read(), Mode.WRITE));
        assertEquals("ABC", Printer.print(r.read(), Mode.WRITE));
        assertEquals("strasse", Printer.print(Reader.forString("#!fold-case STRAßE", symbols, false).read(), Mode.DISPLAY));
    }

    @Test
    public void stringsAndCharacters() {
        assertEquals("\"a\\nb\"", roundTrip("\"a\\nb\""));
        assertEquals("\"line 1continued\"", roundTrip("\"line 1\\   \n   continued\""));
        assertEquals("\"line 1continued\"", roundTrip("\"line 1\\\r\ncontinued\""));
        assertEquals("\"λ\"", roundTrip("\"\\x3BB;\""));
        assertEquals("#\\(", roundTrip("#\\("));
        assertEquals("#\\x", roundTrip("#\\x"));
        assertEquals("#\\A", roundTrip("#\\x41"));
        assertEquals("#\\space", roundTrip("#\\ "));
        readError("\"abc");
        readError("\"\\q\"");
        readError("#\\nosuchname");
        readError("#\\xD800");
    }

    @Test
    public void listsVectorsAndLabels() {
        assertEquals("(1 (2 #(3 #u8(4 255))) . 5)", roundTrip("(1 (2 #(3 #u8(4 255))) . 5)"));
        assertEquals("(quote (quasiquote ((unquote a) (unquote-splicing b))))", roundTrip("'`(,a ,@b)"));
        assertEquals("#0=(1 2 . #0#)", roundTrip("#0=(1 2 . #0#)"));
        assertEquals("#0=#(1 #0#)", roundTrip("#0=#(1 #0#)"));
        assertEquals("(#0=(a) #0#)", Printer.print(read("(#1=(a) #1#)"), Mode.WRITE_SHARED));
        assertEquals("#0=(#1=(x . #0#) #1#)", Printer.print(read("#5=(#6=(x . #5#) #6#)"), Mode.WRITE_SHARED));
        readError("#0#");
        readError("#0=#0#");
        readError("(#0=a #0=b)");
        readError("(1 . 2 3)");
        readError("( . 1)");
        readError("(1 .)");
        readError("(1");
        readError(")");
        readError("#(1 . 2)");
        readError("#u8(256)");
        readError("#u8(a)");
        readError("'");
        readError("#;");
    }

    @Test
    public void commentsAndEndOfInput() {
        Reader r = Reader.forString("  ; comment\n #| a #| nested |# b |# 1 #;(skip me) 2 #; #; 3 4 5", symbols, false);
        assertEquals(1L, r.read());
        assertEquals(2L, r.read());
        assertEquals(5L, r.read());
        assertSame(SConstant.EOF, r.read());
        assertSame(SConstant.EOF, r.read());
        assertSame(SConstant.EOF, read("   ; only a comment"));
        readError("#| unterminated");
        readError("#!no-such-directive");
    }

    @Test
    public void lineCommentsEndAtAnyLineEnding() {
        // R7RS §7.1.1: a line ending is \n, \r or \r\n (M4-12, Larceny's read tests).
        Reader r = Reader.forString("yes; this is a comment \nbut;\r\n this is;too\rnot", symbols, false);
        assertEquals("yes but this is not", String.join(" ", Printer.print(r.read(), Mode.WRITE), Printer.print(r.read(), Mode.WRITE), Printer.print(r.read(), Mode.WRITE),
                        Printer.print(r.read(), Mode.WRITE), Printer.print(r.read(), Mode.WRITE)));
        assertSame(SConstant.EOF, r.read());
    }

    @Test
    public void errorsReportTheLine() {
        assertTrue(readError("\n\n(1 . 2 3)").getMessage().contains("line 3"));
    }

    @Test
    public void literalsAreImmutableButReadDataIsNot() {
        SString literal = (SString) Reader.forString("\"abc\"", symbols, true).read();
        assertTrue(literal.isImmutable());
        assertTrue(((SVector) Reader.forString("#(1)", symbols, true).read()).immutable);
        assertTrue(((SBytevector) Reader.forString("#u8(1)", symbols, true).read()).immutable);
        assertFalse(((SString) read("\"abc\"")).isImmutable());
        assertFalse(((SVector) read("#(1)")).immutable);
    }

    /** Rule 4: no Java recursion proportional to nesting depth. */
    @Test
    public void deepNestingDoesNotRecurse() {
        int depth = 1_000_000;
        Object nested = read("(".repeat(depth) + ")".repeat(depth));
        // depth parens make depth - 1 pairs: the innermost () is the empty list.
        for (int i = 0; i < depth - 2; i++) {
            nested = ((SPair) nested).car;
        }
        assertSame(SConstant.EMPTY_LIST, ((SPair) nested).car);
        assertEquals(depth, Printer.print(read("'".repeat(depth) + "x"), Mode.WRITE).chars().filter(c -> c == '(').count());
        assertEquals("#(" + "#(".repeat(depth - 1) + ")".repeat(depth), roundTrip("#(".repeat(depth) + ")".repeat(depth)));
        assertEquals(42L, read("#|".repeat(depth) + "|#".repeat(depth) + " 42"));
        assertEquals(42L, read("#;".repeat(depth) + "1 ".repeat(depth) + "42"));
        StringBuilder longList = new StringBuilder("(");
        for (int i = 0; i < depth; i++) {
            longList.append("0 ");
        }
        assertEquals(2 * depth + 1, roundTrip(longList.append(")").toString()).length());
    }
}
