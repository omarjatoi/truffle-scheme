package scheme.test;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.graalvm.polyglot.Context;
import org.junit.Test;

/**
 * M12-04: the fast paths that keep the native launcher's steady state off {@code BigInteger}
 * arithmetic, which SubstrateVM doesn't intrinsify (design.md D17, "The native launcher's steady
 * state"), give what the general paths gave.
 */
public class NativeSteadyStateTest {

    private static String write(String expr) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("scheme").out(out).build()) {
            context.eval("scheme", "(write " + expr + ")");
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** A flonum's literal that reads back as the same flonum. */
    private static String literal(double d) {
        String s = Double.toString(d).replace("E", "e");
        return s.contains(".") || s.contains("e") ? s : s + ".";
    }

    /** The exact number a flonum denotes, as R7RS writes it: from its decimal expansion. */
    private static String exactly(double d) {
        BigDecimal b = new BigDecimal(d);
        if (b.scale() <= 0) {
            return b.toBigIntegerExact().toString();
        }
        BigInteger n = b.unscaledValue();
        BigInteger m = BigInteger.TEN.pow(b.scale());
        BigInteger g = n.gcd(m);
        n = n.divide(g);
        m = m.divide(g);
        return m.equals(BigInteger.ONE) ? n.toString() : n + "/" + m;
    }

    /** Edge cases (zeros, 2^52, 2^53, 2^63 and 2^64 and their neighbours, subnormals), and random flonums. */
    private static List<Double> flonums() {
        List<Double> xs = new ArrayList<>(List.of(0.0, -0.0, 1.0, -1.0, 0.5, -0.5, 0.1, -0.1, 1.5, 255.0, 127.49999999999999,
                        0x1p52, 0x1p52 + 0.5, 0x1p53, 0x1p53 + 2, -0x1p53, 0x1p62, 0x1p63, -0x1p63, 0x1p64, -0x1p64, 0x1.fffffffffffffp62,
                        -0x1.fffffffffffffp62, 1e308, -1e308, Double.MAX_VALUE, Double.MIN_VALUE, -Double.MIN_VALUE, Double.MIN_NORMAL,
                        0x0.0000000000003p-1022, 1e-300, 3.141592653589793, -2.718281828459045, 9.007199254740993e15));
        Random random = new Random(1204);
        for (int i = 0; i < 100; i++) {
            xs.add(Double.longBitsToDouble(random.nextLong()));
            xs.add((random.nextDouble() - 0.5) * Math.pow(2, random.nextInt(140) - 70));
        }
        xs.removeIf(d -> !Double.isFinite(d));
        return xs;
    }

    /** {@code exact} of a flonum is the rational it denotes, in lowest terms, whatever its size. */
    @Test
    public void exactOfAFlonumIsItsValue() {
        StringBuilder expr = new StringBuilder("(map exact '(");
        StringBuilder expected = new StringBuilder("(");
        for (double d : flonums()) {
            expr.append(' ').append(literal(d));
            expected.append(expected.length() > 1 ? " " : "").append(exactly(d));
        }
        assertEquals(expected.append(')').toString(), write(expr.append("))").toString()));
    }

    private static final long[] FIXNUMS = {0, 1, -1, 2, 255, (1L << 53) - 1, 1L << 53, (1L << 53) + 1, -(1L << 53) - 1, (1L << 62) + 1,
                    Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1};

    /**
     * A flonum against an exact integer compares their exact values: never the integer rounded to
     * a flonum, which would make 2^53 + 1 equal to the flonum 2^53 (R7RS §6.2.6: comparisons are
     * transitive), and not a flonum truncated to a {@code long} around 2^63, where that saturates.
     */
    @Test
    public void flonumsAgainstExactIntegersCompareExactly() {
        List<String> exacts = new ArrayList<>();
        for (long l : FIXNUMS) {
            exacts.add(Long.toString(l));
        }
        exacts.add(BigInteger.TWO.pow(63).toString());
        exacts.add(BigInteger.TWO.pow(63).negate().subtract(BigInteger.ONE).toString());
        exacts.add(BigInteger.TWO.pow(64).toString());
        exacts.add(BigInteger.TWO.pow(200).add(BigInteger.ONE).negate().toString());
        StringBuilder expr = new StringBuilder("(map (lambda (x) (map (lambda (e) (list (< x e) (= x e) (> e x))) '(");
        expr.append(String.join(" ", exacts)).append("))) '(");
        StringBuilder expected = new StringBuilder("(");
        for (double d : flonums()) {
            expr.append(' ').append(literal(d));
            BigDecimal x = new BigDecimal(d);
            expected.append(expected.length() > 1 ? " (" : "(");
            for (int i = 0; i < exacts.size(); i++) {
                int c = x.compareTo(new BigDecimal(exacts.get(i)));
                expected.append(i > 0 ? " " : "").append(c < 0 ? "(#t #f #t)" : c == 0 ? "(#f #t #f)" : "(#f #f #f)");
            }
            expected.append(')');
        }
        assertEquals(expected.append(')').toString(), write(expr.append("))").toString()));
    }

    /** The sign predicates on flonums, whose generic path compares with the exact zero. */
    @Test
    public void signsOfFlonums() {
        assertEquals("((#t #f #f) (#f #t #f) (#f #f #t) (#f #f #t) (#f #f #f))",
                        write("(map (lambda (x) (list (negative? x) (positive? x) (zero? x))) (list -1e-300 4.9e-324 0. -0. +nan.0))"));
        assertEquals("(#t #f 1.5 -2 2)", write("(list (< 0.5 1 1.5) (> 1e308 (expt 2 1100)) (max 1 1.5) (min 3 -2) (max 2 1))"));
    }
}
