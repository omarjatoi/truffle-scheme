package scheme.builtins;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.math.BigInteger;
import java.nio.ByteOrder;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Division of exact integers (R7RS §6.2.6's {@code quotient}, {@code remainder}, {@code modulo}
 * and the {@code floor/} and {@code truncate/} families) where the JDK's is slow (plan.md M13-06,
 * design.md D1 "Integer division").
 *
 * <p>{@link BigInteger#divideAndRemainder} divides by Knuth's algorithm D on 32-bit digits
 * ({@code MutableBigInteger.divideKnuth}) unless the divisor has at least 80 of them and the
 * quotient 40 (Burnikel-Ziegler). Here, algorithm D runs on 64-bit digits: a
 * quarter as many steps in its inner loop (multiply and subtract), each a 64x64-bit product
 * ({@link Math#unsignedMultiplyHigh}, an intrinsic of Graal on AArch64 and x86-64), and each
 * quotient digit estimated by Möller and Granlund's 2/1 division with a reciprocal of the
 * divisor's top digit ("Improved division by invariant integers", IEEE Transactions on Computers
 * 60(2), 2011, Algorithm 4), with no 128-bit division. The digits come from and go back to a
 * {@code BigInteger} through its two's-complement bytes, which is linear and so cheap beside the
 * quadratic division, except for small operands, which the JDK keeps.
 */
final class ArithmeticDivision {

    private ArithmeticDivision() {
    }

    /**
     * The smallest divisor and quotient, in 32-bit digits (the JDK's), that this division takes:
     * below either, the conversions to and from 64-bit digits cost more than the division saves
     * (design.md D1, "Integer division": at 8 and 8 it takes 0.9 times the JDK's time, at 16 and 16
     * 0.5, at 52 and 52 0.3; at 6 and 6 1.3, at 8 and 4 1.2, and at 52 and 2 1.3).
     */
    static final int MIN_DIVISOR_INTS = 8;
    static final int MIN_QUOTIENT_INTS = 8;

    /**
     * The JDK's Burnikel-Ziegler thresholds ({@code BigInteger.BURNIKEL_ZIEGLER_THRESHOLD} and
     * {@code _OFFSET}, in 32-bit digits): a divisor of 80 digits and a dividend of 40 more. Past
     * both, the JDK's division is subquadratic, and this one, quadratic, still takes 0.45 times its
     * time at 80 digits and 0.65 at 256, but 0.95 at 400 and 1.35 at 640: it takes divisors of up to
     * {@link #MAX_BZ_DIVISOR_INTS} there.
     */
    static final int JDK_BZ_THRESHOLD = 80;
    static final int JDK_BZ_OFFSET = 40;
    static final int MAX_BZ_DIVISOR_INTS = 256;

    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    /** Whether {@link #divideAndRemainder} divides a by b itself, not the JDK. */
    static boolean taken(BigInteger a, BigInteger b) {
        int bInts = (b.bitLength() + 31) >>> 5;
        int qInts = ((a.bitLength() + 31) >>> 5) - bInts;
        if (bInts < MIN_DIVISOR_INTS || qInts < MIN_QUOTIENT_INTS) {
            return false;
        }
        return bInts < JDK_BZ_THRESHOLD || qInts < JDK_BZ_OFFSET || bInts <= MAX_BZ_DIVISOR_INTS;
    }

    /** {@code {a / b, a % b}}, truncated, as {@link BigInteger#divideAndRemainder}. b is not 0. */
    @TruffleBoundary
    static BigInteger[] divideAndRemainder(BigInteger a, BigInteger b) {
        return taken(a, b) ? knuth(a, b) : a.divideAndRemainder(b);
    }

    /**
     * Algorithm D on 64-bit digits; b has at least two. Its steps are small methods of their own,
     * as the JDK's are ({@code mulsub}, {@code divadd}): on the JVM, the host compiler compiles each
     * once it has been called often enough, which a quotient's digits soon do, where one method
     * with the loops would run them in the bytecode interpreter until on-stack replacement, and go
     * back to it whenever a rare step (an add-back) first happens in compiled code (design.md D1,
     * "Integer division").
     */
    static BigInteger[] knuth(BigInteger a, BigInteger b) {
        long[] u = digits(a, 1);
        long[] v = digits(b, 0);
        int n = v.length;
        int m = u.length - 1 - n;
        if (m < 0) {
            return new BigInteger[]{BigInteger.ZERO, a};
        }
        // D1: normalize, so that v's top digit has its top bit set; u gets a digit more.
        int s = Long.numberOfLeadingZeros(v[n - 1]);
        if (s != 0) {
            shiftLeft(v, n, s);
            u[m + n] = u[m + n - 1] >>> -s;
            shiftLeft(u, m + n, s);
        }
        long[] q = new long[m + 1];
        long v1 = v[n - 1];
        long v2 = v[n - 2];
        long inverse = reciprocal(v1);
        for (int j = m; j >= 0; j--) {
            long qhat = quotientDigit(u[j + n], u[j + n - 1], u[j + n - 2], v1, v2, inverse);
            // D4: u[j .. j+n] -= q̂ v.
            long borrow = multiplySubtract(u, v, n, j, qhat);
            long t = u[j + n];
            u[j + n] = t - borrow;
            if (Long.compareUnsigned(t, borrow) < 0) {
                // D6: q̂ was one too large (probability about 2/B): add v back.
                qhat--;
                u[j + n] += add(u, v, n, j);
            }
            q[j] = qhat;
        }
        // D8: the remainder is u[0 .. n-1], unnormalized.
        if (s != 0) {
            for (int i = 0; i < n - 1; i++) {
                u[i] = (u[i] >>> s) | (u[i + 1] << -s);
            }
            u[n - 1] >>>= s;
        }
        return new BigInteger[]{bigInteger(q, m + 1, a.signum() * b.signum()), bigInteger(u, n, a.signum())};
    }

    /** x[0 .. len-1] shifted left by s (0 < s < 64) in place, the top bits dropped. */
    private static void shiftLeft(long[] x, int len, int s) {
        for (int i = len - 1; i > 0; i--) {
            x[i] = (x[i] << s) | (x[i - 1] >>> -s);
        }
        x[0] <<= s;
    }

    /**
     * D3: the quotient digit q̂ of (u2, u1, u0) by (v1, v2), estimated from u's top two digits and
     * v1, then tested against v2: too large by at most one.
     */
    private static long quotientDigit(long u2, long u1, long u0, long v1, long v2, long inverse) {
        long qhat;
        long rhat;
        if (u2 == v1) {
            // The quotient would be B or more (it is at most B + 1): B - 1, r̂ = u1 + v1. Once r̂
            // is B or more, q̂ v2 < r̂ B and the test can't fail.
            qhat = -1L;
            rhat = u1 + v1;
            if (Long.compareUnsigned(rhat, v1) < 0) {
                return qhat;
            }
        } else {
            // u2 < v1: Möller and Granlund's 2/1 division of (u2, u1) by v1.
            long p0 = inverse * u2;
            long p1 = Math.unsignedMultiplyHigh(inverse, u2);
            long q0 = p0 + u1;
            long q1 = p1 + u2 + (Long.compareUnsigned(q0, p0) < 0 ? 1 : 0) + 1;
            long r = u1 - q1 * v1;
            if (Long.compareUnsigned(r, q0) > 0) {
                q1--;
                r += v1;
            }
            if (Long.compareUnsigned(r, v1) >= 0) {
                q1++;
                r -= v1;
            }
            qhat = q1;
            rhat = r;
        }
        // While q̂ v2 > r̂ B + u0, q̂ is too large (twice at most); not once r̂ >= B.
        if (greater(qhat, v2, rhat, u0)) {
            qhat--;
            rhat += v1;
            if (Long.compareUnsigned(rhat, v1) >= 0 && greater(qhat, v2, rhat, u0)) {
                qhat--;
            }
        }
        return qhat;
    }

    /** u[j .. j+n-1] -= q v[0 .. n-1]; the borrow out of digit j+n-1. */
    private static long multiplySubtract(long[] u, long[] v, int n, int j, long q) {
        long borrow = 0;
        for (int i = 0; i < n; i++) {
            long d = v[i];
            long lo = q * d + borrow;
            long hi = Math.unsignedMultiplyHigh(q, d) + (Long.compareUnsigned(lo, borrow) < 0 ? 1 : 0);
            long t = u[i + j];
            u[i + j] = t - lo;
            borrow = hi + (Long.compareUnsigned(t, lo) < 0 ? 1 : 0);
        }
        return borrow;
    }

    /** u[j .. j+n-1] += v[0 .. n-1]; the carry out of digit j+n-1. */
    private static long add(long[] u, long[] v, int n, int j) {
        long carry = 0;
        for (int i = 0; i < n; i++) {
            long x = u[i + j];
            long sum = x + v[i] + carry;
            carry = Long.compareUnsigned(sum, x) < 0 || (carry != 0 && sum == x) ? 1 : 0;
            u[i + j] = sum;
        }
        return carry;
    }

    /** Whether the 128-bit product q d is above r B + u (all unsigned). */
    private static boolean greater(long q, long d, long r, long u) {
        long hi = Math.unsignedMultiplyHigh(q, d);
        return Long.compareUnsigned(hi, r) > 0 || hi == r && Long.compareUnsigned(q * d, u) > 0;
    }

    /**
     * Möller and Granlund's reciprocal of a normalized digit d (its top bit set): floor((B² - 1) /
     * d) - B, which is (B - 1 - d, B - 1) / d, a quotient that fits in a digit.
     */
    static long reciprocal(long d) {
        return divide128(~d, -1L, d);
    }

    /**
     * floor((u1 B + u0) / d) for a normalized d and u1 < d (all unsigned): Knuth's algorithm D on
     * 32-bit halves (Hacker's Delight, 2nd ed., figure 9-3, divlu), two 64/32 steps.
     */
    static long divide128(long u1, long u0, long d) {
        long dh = d >>> 32;
        long dl = d & 0xffffffffL;
        long u0h = u0 >>> 32;
        long u0l = u0 & 0xffffffffL;
        long q1 = Long.divideUnsigned(u1, dh);
        long rh = u1 - q1 * dh;
        while (Long.compareUnsigned(q1, 0xffffffffL) > 0 || Long.compareUnsigned(q1 * dl, (rh << 32) | u0h) > 0) {
            q1--;
            rh += dh;
            if (Long.compareUnsigned(rh, 0xffffffffL) > 0) {
                break;
            }
        }
        long u21 = ((u1 << 32) | u0h) - q1 * d;
        long q0 = Long.divideUnsigned(u21, dh);
        rh = u21 - q0 * dh;
        while (Long.compareUnsigned(q0, 0xffffffffL) > 0 || Long.compareUnsigned(q0 * dl, (rh << 32) | u0l) > 0) {
            q0--;
            rh += dh;
            if (Long.compareUnsigned(rh, 0xffffffffL) > 0) {
                break;
            }
        }
        return (q1 << 32) | q0;
    }

    /** |x|'s 64-bit digits, least significant first, and {@code extra} zero digits above them. */
    static long[] digits(BigInteger x, int extra) {
        BigInteger abs = x.abs();
        byte[] bytes = abs.toByteArray();
        int len = (abs.bitLength() + 63) >>> 6;
        long[] r = new long[len + extra];
        int i = 0;
        int end = bytes.length;
        for (; i < len && end >= Long.BYTES; end -= Long.BYTES) {
            r[i++] = (long) LONGS.get(bytes, end - Long.BYTES);
        }
        if (i < len) {
            long top = 0;
            for (int k = 0; k < end; k++) {
                top = (top << 8) | (bytes[k] & 0xff);
            }
            r[i] = top;
        }
        return r;
    }

    /** The integer with x[0 .. len-1]'s digits as its magnitude, with signum's sign. */
    static BigInteger bigInteger(long[] x, int len, int signum) {
        int n = len;
        while (n > 0 && x[n - 1] == 0) {
            n--;
        }
        if (n == 0) {
            return BigInteger.ZERO;
        }
        byte[] bytes = new byte[n * Long.BYTES];
        for (int i = 0; i < n; i++) {
            LONGS.set(bytes, (n - 1 - i) * Long.BYTES, x[i]);
        }
        return new BigInteger(signum, bytes);
    }
}
