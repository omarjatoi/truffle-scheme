package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.Test;

import com.oracle.truffle.api.source.Source;

import scheme.reader.Reader;
import scheme.reader.Scope;
import scheme.reader.ScopeSet;
import scheme.reader.Syntax;
import scheme.runtime.printer.Printer;
import scheme.runtime.printer.Printer.Mode;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SVector;
import scheme.runtime.value.SymbolTable;

/** M3-01: syntax objects from the reader, with source positions and scopes, and stripping them to data. */
public class SyntaxTest {

    private final SymbolTable symbols = new SymbolTable();

    private Syntax readSyntax(String text) {
        Source source = Source.newBuilder("scheme", text, "test.scm").build();
        return (Syntax) Reader.forSource(source, symbols, true).readSyntax();
    }

    @Test
    public void syntaxObjectsCarrySourcePositions() {
        Syntax form = readSyntax("(define x\n  (f #(1 2) 'q \"s\"))");
        assertEquals(1, form.source.getStartLine());
        assertEquals("(define x\n  (f #(1 2) 'q \"s\"))", form.source.getCharacters().toString());
        SPair list = (SPair) form.datum;
        Syntax define = (Syntax) list.car;
        assertSame(symbols.intern("define"), define.datum);
        assertEquals(2, define.source.getStartColumn());
        Syntax call = (Syntax) ((SPair) ((SPair) list.cdr).cdr).car;
        assertEquals(2, call.source.getStartLine());
        assertEquals(3, call.source.getStartColumn());
        Syntax vector = (Syntax) ((SPair) ((SPair) call.datum).cdr).car;
        assertEquals("#(1 2)", vector.source.getCharacters().toString());
        assertTrue(((SVector) vector.datum).get(0) instanceof Syntax);
        Syntax quoted = (Syntax) ((SPair) ((SPair) ((SPair) call.datum).cdr).cdr).car;
        assertEquals("'q", quoted.source.getCharacters().toString());
        assertTrue(form.scopes.isEmpty());
    }

    @Test
    public void strippingGivesTheDatum() {
        String text = "(a (b . c) #(1 \"x\" #\\y) 'q `(u ,v) #u8(1 2) ())";
        Object stripped = Syntax.toDatum(readSyntax(text));
        Object read = Reader.forString(text, symbols, true).read();
        assertEquals(Printer.print(read, Mode.WRITE), Printer.print(stripped, Mode.WRITE));
        assertSame(SConstant.EMPTY_LIST, Syntax.toDatum(readSyntax("()")));
        assertEquals(42L, Syntax.toDatum(readSyntax("42")));
        assertSame(SConstant.EOF, Reader.forSource(Source.newBuilder("scheme", "  ", "e.scm").build(), symbols, true).readSyntax());
    }

    @Test
    public void strippingKeepsSharingAndCycles() {
        assertEquals("#0=(a . #0#)", Printer.print(Syntax.toDatum(readSyntax("#0=(a . #0#)")), Mode.WRITE));
        assertEquals("(#0=(x) #0#)", Printer.print(Syntax.toDatum(readSyntax("(#1=(x) #1#)")), Mode.WRITE_SHARED));
        assertEquals("#0=#(1 #0#)", Printer.print(Syntax.toDatum(readSyntax("#0=#(1 #0#)")), Mode.WRITE));
    }

    /** Rule 4: no Java recursion proportional to nesting depth. */
    @Test
    public void deepStrippingDoesNotRecurse() {
        int depth = 1_000_000;
        Object stripped = Syntax.toDatum(readSyntax("(".repeat(depth) + ")".repeat(depth)));
        int count = 1;
        for (Object o = stripped; o instanceof SPair p && p.car instanceof SPair; o = p.car) {
            count++;
        }
        assertEquals(depth - 1, count);
    }

    @Test
    public void scopeSets() {
        Scope a = new Scope();
        Scope b = new Scope();
        assertNotSame(a, b);
        ScopeSet empty = ScopeSet.EMPTY;
        ScopeSet ab = empty.add(a).add(b);
        assertEquals(2, ab.size());
        assertTrue(ab.contains(a) && ab.contains(b));
        assertSame(ab, ab.add(a));
        assertTrue(empty.add(a).isSubsetOf(ab));
        assertFalse(ab.isSubsetOf(empty.add(a)));
        assertEquals(ab.remove(a), empty.add(b));
        assertEquals(ab.flip(a), empty.add(b));
        assertEquals(ab.flip(a).flip(a), ab);
        assertTrue(empty.isEmpty());
    }

    @Test
    public void compileErrorsNameTheLine() {
        try (Context context = Context.create("scheme")) {
            PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval("scheme", "(define x 1)\n\n(lambda (y y) y)"));
            assertTrue(e.getMessage(), e.getMessage().contains("line 3"));
            PolyglotException e2 = assertThrows(PolyglotException.class, () -> context.eval("scheme", "(display 1)\n(if)"));
            assertTrue(e2.getMessage(), e2.getMessage().contains("line 2"));
        }
    }
}
