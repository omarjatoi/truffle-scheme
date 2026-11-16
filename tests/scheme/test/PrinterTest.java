package scheme.test;

import static org.junit.Assert.assertEquals;

import java.math.BigInteger;

import org.junit.Test;

import scheme.runtime.printer.Printer;
import scheme.runtime.printer.Printer.Mode;
import scheme.runtime.value.SBigInteger;
import scheme.runtime.value.SBytevector;
import scheme.runtime.value.SChar;
import scheme.runtime.value.SComplex;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SRational;
import scheme.runtime.value.SString;
import scheme.runtime.value.SSymbol;
import scheme.runtime.value.SVector;
import scheme.runtime.value.SymbolTable;

/**
 * M1-03: write, display, write-shared and write-simple. Expected strings are chibi-scheme 0.12's
 * output wherever R7RS leaves the format open, and R7RS's where it doesn't (display, bytevectors).
 */
public class PrinterTest {

    private final SymbolTable symbols = new SymbolTable();

    private static String write(Object o) {
        return Printer.print(o, Mode.WRITE);
    }

    private static String display(Object o) {
        return Printer.print(o, Mode.DISPLAY);
    }

    private SSymbol sym(String name) {
        return symbols.intern(name);
    }

    private static Object list(Object... items) {
        Object result = SConstant.EMPTY_LIST;
        for (int i = items.length - 1; i >= 0; i--) {
            result = new SPair(items[i], result);
        }
        return result;
    }

    @Test
    public void exactNumbers() {
        assertEquals("42", write(42L));
        assertEquals("-7", write(-7L));
        assertEquals("123456789012345678901234567890", write(SBigInteger.normalize(new BigInteger("123456789012345678901234567890"))));
        assertEquals("3/4", write(SRational.make(BigInteger.valueOf(3), BigInteger.valueOf(4))));
        assertEquals("-1/3", write(SRational.make(BigInteger.valueOf(2), BigInteger.valueOf(-6))));
    }

    /** chibi-scheme's flonum format: shortest of %.15g, %.16g, %.17g that reads back, plus ".0". */
    @Test
    public void flonums() {
        assertEquals("1.0", write(1.0));
        assertEquals("0.1", write(0.1));
        assertEquals("100.0", write(100.0));
        assertEquals("10000000000.0", write(1e10));
        assertEquals("123456789012345.0", write(123456789012345.0));
        assertEquals("1e+15", write(1e15));
        assertEquals("1e+21", write(1e21));
        assertEquals("1e+22", write(1e22));
        assertEquals("1.5e-07", write(1.5e-7));
        assertEquals("123.456", write(123.456));
        assertEquals("-0.0", write(-0.0));
        assertEquals("0.0", write(0.0));
        assertEquals("+inf.0", write(Double.POSITIVE_INFINITY));
        assertEquals("-inf.0", write(Double.NEGATIVE_INFINITY));
        assertEquals("+nan.0", write(Double.NaN));
        assertEquals("0.3333333333333333", write(1.0 / 3));
        assertEquals("9007199254740992.0", write(9007199254740992.0));
        assertEquals("1e-05", write(1e-5));
        assertEquals("0.0001", write(1e-4));
        assertEquals("-2.5", write(-2.5));
        assertEquals("1.7976931348623157e+308", write(Double.MAX_VALUE));
        assertEquals("4.94065645841247e-324", write(Double.MIN_VALUE));
        assertEquals("2.2250738585072014e-308", write(Double.MIN_NORMAL));
        assertEquals("1e+100", write(1e100));
        assertEquals("-1.5e-300", write(-1.5e-300));
        assertEquals("6.02214076e+23", write(6.02214076e23));
        assertEquals("1234567890123456.0", write(1234567890123456.0));
        assertEquals("0.30000000000000004", write(0.1 + 0.2));
    }

    @Test
    public void complexNumbers() {
        assertEquals("1+2i", write(SComplex.make(1L, 2L)));
        assertEquals("1.5-2.5i", write(SComplex.make(1.5, -2.5)));
        assertEquals("0+i", write(SComplex.make(0L, 1L)));
        assertEquals("0-i", write(SComplex.make(0L, -1L)));
        assertEquals("0.0-1.0i", write(SComplex.make(0.0, -1.0)));
        assertEquals("1.0+inf.0i", write(SComplex.make(1.0, Double.POSITIVE_INFINITY)));
        assertEquals("1.0-inf.0i", write(SComplex.make(1.0, Double.NEGATIVE_INFINITY)));
        assertEquals("1.0+nan.0i", write(SComplex.make(1.0, Double.NaN)));
    }

    @Test
    public void constants() {
        assertEquals("#t", write(true));
        assertEquals("#f", write(false));
        assertEquals("()", write(SConstant.EMPTY_LIST));
        assertEquals("#<undef>", write(SConstant.UNSPECIFIED));
        assertEquals("#<eof>", write(SConstant.EOF));
    }

    @Test
    public void characters() {
        assertEquals("#\\a", write(SChar.of('a')));
        assertEquals("a", display(SChar.of('a')));
        String[][] named = {{" ", "space"}, {"\n", "newline"}, {"\t", "tab"}, {"\0", "null"}, {"\u0007", "alarm"},
                        {"\b", "backspace"}, {"\u007f", "delete"}, {"\u001b", "escape"}, {"\r", "return"}};
        for (String[] n : named) {
            assertEquals("#\\" + n[1], write(SChar.of(n[0].codePointAt(0))));
            assertEquals(n[0], display(SChar.of(n[0].codePointAt(0))));
        }
        assertEquals("#\\λ", write(SChar.of(0x3bb)));
        assertEquals("#\\x1", write(SChar.of(1)));
        assertEquals("#\\ ", write(SChar.of(0xa0)));
        assertEquals("#\\😀", write(SChar.of(0x1F600)));
    }

    @Test
    public void strings() {
        assertEquals("\"a\\\"b\\\\c\"", write(SString.of("a\"b\\c")));
        assertEquals("a\"b\\c", display(SString.of("a\"b\\c")));
        assertEquals("\"x\\ny\"", write(SString.of("x\ny")));
        assertEquals("\"x\\ty\"", write(SString.of("x\ty")));
        assertEquals("\"x\\ry\"", write(SString.of("x\ry")));
        assertEquals("\"a\\a\"", write(SString.of("a\u0007")));
        assertEquals("\"a\\b\"", write(SString.of("a\b")));
        assertEquals("\"a\\x01;\"", write(SString.of("a\u0001")));
        assertEquals("\"\\x1b;\\x00;\"", write(SString.of("\u001b\0")));
        assertEquals("\"λ\u007f\"", write(SString.of("λ\u007f")));
        assertEquals("\"😀\"", write(SString.of("😀")));
    }

    /** The suite's test-write-syntax cases (tests/r7rs/6.13.scm), plus chibi's output for more. */
    @Test
    public void symbols() {
        String[][] cases = {
                        {".", "|.|"}, {"a b", "|a b|"}, {",a", "|,a|"}, {"\"", "|\"|"}, {"|", "|\\||"}, {"", "||"},
                        {"\\123", "|\\\\123|"}, {"a", "a"}, {"2", "|2|"}, {"+3", "|+3|"}, {"-.4", "|-.4|"},
                        {"+i", "|+i|"}, {"-i", "|-i|"}, {"+inf.0", "|+inf.0|"}, {"-inf.0", "|-inf.0|"},
                        {"+nan.0", "|+nan.0|"}, {"+NaN.0", "|+NaN.0|"}, {"+NaN.0abc", "|+NaN.0abc|"},
                        {"hello world", "|hello world|"}, {"123", "|123|"}, {"ABC", "ABC"}, {"#foo", "|#foo|"},
                        {"a(b", "|a(b|"}, {"+", "+"}, {"...", "..."}, {"+a", "+a"}, {"1+", "|1+|"}, {"λ", "|λ|"},
                        {"a'b", "|a'b|"}, {"a[b", "|a[b|"}, {"a`b", "a`b"}, {"a\u007fb", "a\u007fb"}, {"-", "-"},
        };
        for (String[] c : cases) {
            assertEquals("write of symbol \"" + c[0] + "\"", c[1], write(sym(c[0])));
        }
        // R7RS display: symbols are not escaped.
        assertEquals("hello world", display(sym("hello world")));
        assertEquals("", display(sym("")));
    }

    @Test
    public void listsAndVectors() {
        assertEquals("(1 2 3)", write(list(1L, 2L, 3L)));
        assertEquals("(1 . 2)", write(new SPair(1L, 2L)));
        assertEquals("(1 2 . 3)", write(new SPair(1L, new SPair(2L, 3L))));
        assertEquals("(quote a)", write(list(sym("quote"), sym("a"))));
        Object nested = list(1L, list(2L, SString.of("s"), SChar.of('c')), new SVector(new Object[]{3L}));
        assertEquals("(1 (2 \"s\" #\\c) #(3))", write(nested));
        // R7RS display: nested strings as if by write-string, characters as if by write-char.
        assertEquals("(1 (2 s c) #(3))", display(nested));
        assertEquals("#(1 \"x\" #\\y)", write(new SVector(new Object[]{1L, SString.of("x"), SChar.of('y')})));
        assertEquals("#()", write(new SVector(new Object[0])));
        assertEquals("#u8(1 2 255)", write(new SBytevector(new byte[]{1, 2, (byte) 255})));
        assertEquals("#u8()", write(new SBytevector(new byte[0])));
        assertEquals("(())", write(list(SConstant.EMPTY_LIST)));
    }

    @Test
    public void cyclesAndSharing() {
        SPair cycle = (SPair) list(1L, 2L, 3L);
        ((SPair) ((SPair) cycle.cdr).cdr).cdr = cycle;
        assertEquals("#0=(1 2 3 . #0#)", write(cycle));
        assertEquals("#0=(1 2 3 . #0#)", display(cycle));
        assertEquals("#0=(1 2 3 . #0#)", Printer.print(cycle, Mode.WRITE_SHARED));

        SVector vector = new SVector(new Object[]{1L, 2L});
        vector.set(1, vector);
        assertEquals("#0=#(1 #0#)", write(vector));

        Object shared = list(1L, 2L);
        Object twice = list(shared, shared);
        assertEquals("((1 2) (1 2))", write(twice));
        assertEquals("((1 2) (1 2))", Printer.print(twice, Mode.WRITE_SIMPLE));
        assertEquals("(#0=(1 2) #0#)", Printer.print(twice, Mode.WRITE_SHARED));

        SPair carCycle = (SPair) list(sym("a"), sym("b"));
        carCycle.car = carCycle;
        assertEquals("#0=(#0# b)", write(carCycle));

        Object inner = list(sym("x"));
        SPair mixed = (SPair) list(inner, inner);
        ((SPair) mixed.cdr).cdr = mixed;
        assertEquals("#0=((x) (x) . #0#)", write(mixed));
        assertEquals("#0=(#1=(x) #1# . #0#)", Printer.print(mixed, Mode.WRITE_SHARED));
    }

    /** Rule 4: no Java recursion proportional to nesting depth. */
    @Test
    public void deepNestingDoesNotRecurse() {
        int depth = 1_000_000;
        Object carNested = SConstant.EMPTY_LIST;
        for (int i = 0; i < depth; i++) {
            carNested = new SPair(carNested, SConstant.EMPTY_LIST);
        }
        for (Mode mode : Mode.values()) {
            assertEquals(mode.toString(), 2 * depth + 2, Printer.print(carNested, mode).length());
        }
        Object vectorNested = 1L;
        for (int i = 0; i < depth; i++) {
            vectorNested = new SVector(new Object[]{vectorNested});
        }
        assertEquals(3 * depth + 1, write(vectorNested).length());
        Object longList = SConstant.EMPTY_LIST;
        for (int i = 0; i < depth; i++) {
            longList = new SPair(0L, longList);
        }
        assertEquals(2 * depth + 1, Printer.print(longList, Mode.WRITE_SHARED).length());
    }
}
