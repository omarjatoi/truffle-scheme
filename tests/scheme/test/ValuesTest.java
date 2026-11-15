package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;

import org.junit.Test;

import scheme.runtime.SchemeError;
import scheme.runtime.value.MultipleValues;
import scheme.runtime.value.SBigInteger;
import scheme.runtime.value.SChar;
import scheme.runtime.value.SComplex;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SErrorObject;
import scheme.runtime.value.SRational;
import scheme.runtime.value.SRecord;
import scheme.runtime.value.SRecordType;
import scheme.runtime.value.SString;
import scheme.runtime.value.SymbolTable;

/** M1-03: the D1 value types and their canonical forms. */
public class ValuesTest {

    @Test
    public void exactIntegersDemoteToLong() {
        assertEquals(5L, SBigInteger.normalize(BigInteger.valueOf(5)));
        assertEquals(Long.MAX_VALUE, SBigInteger.normalize(BigInteger.valueOf(Long.MAX_VALUE)));
        Object big = SBigInteger.normalize(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE));
        assertTrue(big instanceof SBigInteger);
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE), ((SBigInteger) big).value);
    }

    @Test
    public void rationalsAreNormalized() {
        SRational r = (SRational) SRational.make(BigInteger.valueOf(6), BigInteger.valueOf(-4));
        assertEquals(BigInteger.valueOf(-3), r.numerator);
        assertEquals(BigInteger.valueOf(2), r.denominator);
        assertEquals(2L, SRational.make(BigInteger.valueOf(4), BigInteger.valueOf(2)));
        assertEquals(0L, SRational.make(BigInteger.ZERO, BigInteger.valueOf(7)));
        assertThrows(SchemeError.class, () -> SRational.make(BigInteger.ONE, BigInteger.ZERO));
    }

    @Test
    public void complexNumbersHaveOneExactness() {
        assertEquals(3L, SComplex.make(3L, 0L));
        SComplex inexactZero = (SComplex) SComplex.make(-2.5, 0.0);
        assertEquals(0.0, inexactZero.imag);
        SComplex mixed = (SComplex) SComplex.make(1L, 0.5);
        assertEquals(1.0, mixed.real);
        assertEquals(0.5, mixed.imag);
        SComplex mixedRational = (SComplex) SComplex.make(SRational.make(BigInteger.ONE, BigInteger.valueOf(4)), 2.0);
        assertEquals(0.25, mixedRational.real);
    }

    @Test
    public void charactersBelow256AreCached() {
        assertSame(SChar.of('a'), SChar.of('a'));
        assertSame(SChar.of(255), SChar.of(255));
        assertEquals(0x3bb, SChar.of(0x3bb).codePoint);
        assertThrows(SchemeError.class, () -> SChar.of(0xD800));
        assertThrows(SchemeError.class, () -> SChar.of(0x110000));
    }

    @Test
    public void stringsWidenFromLatin1ToUtf32() {
        SString s = SString.of("abc");
        assertTrue(s.isLatin1());
        s.set(1, 0xe9);
        assertTrue(s.isLatin1());
        s.set(2, 0x3bb);
        assertFalse(s.isLatin1());
        assertEquals(3, s.length());
        assertEquals('a', s.codePointAt(0));
        assertEquals(0xe9, s.codePointAt(1));
        assertEquals(0x3bb, s.codePointAt(2));
        assertEquals("aéλ", s.toString());
        SString astral = SString.of("x😀");
        assertEquals(2, astral.length());
        assertEquals(0x1F600, astral.codePointAt(1));
        assertThrows(SchemeError.class, () -> s.codePointAt(3));
    }

    @Test
    public void literalStringsAreImmutable() {
        SString literal = SString.literal("abc");
        assertTrue(literal.isImmutable());
        assertThrows(SchemeError.class, () -> literal.set(0, 'x'));
    }

    @Test
    public void symbolsAreInternedPerTable() {
        SymbolTable table = new SymbolTable();
        assertSame(table.intern("foo"), table.intern("foo"));
        assertNotSame(table.intern("foo"), table.intern("bar"));
        assertNotSame(table.intern("foo"), new SymbolTable().intern("foo"));
        assertEquals("foo", table.intern("foo").toString());
    }

    @Test
    public void recordsErrorsAndValues() {
        SRecordType point = new SRecordType("point", new String[]{"x", "y"});
        SRecord p = new SRecord(point, new Object[]{1L, 2L});
        assertSame(point, p.type);
        assertEquals(2L, p.fields[1]);

        SErrorObject error = new SErrorObject(SString.of("msg"), SConstant.EMPTY_LIST, SErrorObject.Kind.FILE);
        SchemeError thrown = new SchemeError(error);
        assertSame(error, thrown.getErrorObject());
        assertEquals("msg", thrown.getMessage());
        assertEquals(SErrorObject.Kind.OTHER, new SchemeError("plain").getErrorObject().kind);

        assertEquals(0, new MultipleValues(new Object[0]).values.length);
    }
}
