package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.Test;

import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.dsl.Introspection;
import com.oracle.truffle.api.dsl.Introspection.SpecializationInfo;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;

import scheme.runtime.value.SClosure;

/** M3-08: the numeric procedures sections 4.x and 5 and the test shim need (numeric tower area). */
public class NumbersTest {

    private static String write(String expr) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("scheme").out(out).build()) {
            context.eval("scheme", "(write " + expr + ")");
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void exactRationals() {
        assertEquals("1/3", write("(/ 1 3)"));
        assertEquals("2", write("(/ 6 3)"));
        assertEquals("1/2", write("(+ 1/3 1/6)"));
        assertEquals("1800/497", write("(/ 1800 497)"));
        assertEquals("#t", write("(< 1/3 1/2 1)"));
        assertEquals("0.5", write("(/ 1 2.)"));
        assertEquals("-1/2", write("(/ -2)"));
        assertEquals("#t", write("(= 1/2 0.5)"));
    }

    /**
     * R7RS §6.2.6: "It is an error if any argument of / other than the first is an exact zero", an
     * inexact other argument included (the §6 audit's follow-up; design.md D9). A zero that is
     * inexact divides as IEEE does, and an exact zero dividend is fine.
     */
    @Test
    public void divisionByAnExactZeroRaises() {
        String[] errors = {"(/ 1. 0)", "(/ 0. 0)", "(/ -inf.0 0)", "(/ +nan.0 0)", "(/ 1 2. 0)", "(/ 1. 2 0 3)", "(/ 1.5+2i 0)", "(/ 3 0)", "(/ 0)", "(apply / '(1. 0))"};
        for (String e : errors) {
            assertEquals(e, "(\"/: division by zero\" ())", write("(guard (e ((error-object? e) (list (error-object-message e) (error-object-irritants e)))) " + e + ")"));
        }
        assertEquals("(+inf.0 -inf.0 +nan.0 0.0 0 +inf.0 -inf.0 0.5)", write("(list (/ 1. 0.) (/ -1 0.) (/ 0 0.) (/ 0 2.) (/ 0 5) (/ 0.) (/ 1. 0.25 -0.) (/ 1 2.))"));
    }

    /**
     * The typed {@code Div} operation's flonum path (M7-03) is untouched by that check: an exact zero
     * divisor is a fixnum, which only the generic path (the primitive's) takes. A site that has seen
     * only flonums keeps only its flonum specialization, and raises at an exact zero.
     */
    @Test
    public void flonumDivisionKeepsItsFastPath() {
        try (Context context = Context.newBuilder("scheme").build()) {
            ResultProbe probe = context.getEngine().getInstruments().get(ResultProbe.ID).lookup(ResultProbe.class);
            context.eval("scheme", "(define (site a b) (car (list (/ a b))))");
            for (int i = 0; i < 3; i++) {
                assertEquals(0.75, context.eval("scheme", "(site 1.5 2.)").asDouble(), 0);
                assertEquals(Double.POSITIVE_INFINITY, context.eval("scheme", "(site 1.5 0.)").asDouble(), 0);
            }
            context.eval(Source.newBuilder("scheme", "site", ResultProbe.SOURCE).buildLiteral());
            BytecodeRootNode root = (BytecodeRootNode) ((SClosure) probe.last).callTarget.getRootNode();
            assertEquals(List.of("c.Div$Double"), divisions(root));
            assertEquals("error", context.eval("scheme", "(guard (e ((error-object? e) 'error)) (site 1.5 0))").toString());
            // The exact zero took the generic path: the site now has both.
            assertEquals(List.of("c.Div"), divisions(root));
            assertEquals(0.75, context.eval("scheme", "(site 1.5 2.)").asDouble(), 0);
        }
    }

    /** The names of {@code root}'s {@code Div} instructions, as the interpreter has quickened them. */
    private static List<String> divisions(BytecodeRootNode root) {
        List<String> names = new ArrayList<>();
        for (Instruction instruction : root.getBytecodeNode().getInstructions()) {
            if (instruction.getName().startsWith("c.Div")) {
                names.add(instruction.getName());
            }
        }
        return names;
    }

    @Test
    public void predicates() {
        assertEquals("(#t #t #f #t #t #f)", write("(list (integer? 2) (integer? 2.0) (integer? 1/2) (rational? 1/2) (real? 1.5) (exact? 1.5))"));
        assertEquals("(#t #f #t #t #f)", write("(list (zero? 0) (zero? 1/2) (positive? 1/2) (even? 4) (odd? 4))"));
        assertEquals("(#t #f #t #t)", write("(list (exact-integer? 5) (exact-integer? 5.0) (number? 1/2) (nan? (/ 0. 0.)))"));
    }

    @Test
    public void procedures() {
        assertEquals("(5 5/2 9 0 1/2)", write("(list (abs -5) (abs -5/2) (square 3) (imag-part 3) (real-part 1/2))"));
        assertEquals("(1267650600228229401496703205376 1/8 1.4142135623730951)", write("(list (expt 2 100) (expt 2 -3) (expt 2. .5))"));
        assertEquals("(5 7)", write("(call-with-values (lambda () (exact-integer-sqrt 32)) list)"));
        assertEquals("(\"1100\" \"ff\" \"-7/2\" \"0.25\")", write("(list (number->string 12 2) (number->string 255 16) (number->string -7/2) (number->string .25))"));
        assertEquals("(3 2/3 1.4142135623730951 1/4 #t)", write("(list (sqrt 9) (sqrt 4/9) (sqrt 2) (exact .25) (inexact? (inexact 1/3)))"));
        assertEquals("(1.0 0.0 2.0)", write("(list (exp 0) (log 1) (log 100 10))"));
    }

    /**
     * R7RS §6.2.6: inexact "returns an inexact representation of z. The value returned is the
     * inexact number that is numerically closest to the argument", so a rational rounds once to
     * the nearest flonum, ties to even, also just next to a tie, at the subnormals and at the
     * overflow threshold; the reader's #i rationals alike.
     */
    @Test
    public void inexactRationalsRoundToNearest() {
        assertEquals("(0.2500000000000001 0.125 0.12500000000000006 0.12500000000000003 0.125 0.2500000000000001)", write("""
                        (list (inexact 9007199254740995/36028797018963968) (inexact 9007199254740993/72057594037927936)
                              (inexact 9007199254740997/72057594037927936)
                              (inexact (/ 11417981541647680316116887983825362587765178369 (expt 2 156)))
                              (inexact (/ 11417981541647680316116887983825362587765178367 (expt 2 156)))
                              #i9007199254740995/36028797018963968)"""));
        assertEquals("(#t #t #t 1.7976931348623157e+308 +inf.0 -0.3333333333333333)", write("""
                        (list (eqv? (inexact (/ 3 (expt 2 1075))) 1e-323) (eqv? (inexact (/ 1 (expt 2 1075))) 0.)
                              (eqv? (inexact (/ -1 (expt 2 1076))) -0.)
                              (inexact (/ (- (expt 2 1025) (expt 2 971) 1) 2)) (inexact (/ (+ (- (expt 2 1025) (expt 2 971)) 1) 2))
                              (inexact -1/3))"""));
    }

    /**
     * R7RS §6.2.7: number->string returns "an external representation of the given number in the
     * given radix such that (eqv? number (string->number (number->string number radix) radix)) is
     * true", and "it is an error if no possible result makes this expression true", which "can
     * occur only when z is not a complex number or is a complex number with a non-rational real or
     * imaginary part". Decimals are radix 10 only (§7.1.1 &lt;decimal 10&gt;), so an inexact number in
     * radix 2, 8 or 16 is written as #i and the exact rational it equals; infinities and NaNs are
     * written as in every radix.
     */
    @Test
    public void inexactNumbersInOtherRadixesReadBack() {
        assertEquals("(\"#i1/10\" \"#iff\" \"#i-11/10\" \"#i0\" \"+inf.0\" \"-inf.0\" \"+nan.0\")", write(
                        "(list (number->string .5 2) (number->string 255. 16) (number->string -1.125 8) (number->string 0. 2) (number->string +inf.0 2) (number->string -inf.0 8) (number->string +nan.0 16))"));
        assertEquals("(\"#i11/10+1/100i\" \"#i1+inf.0i\" \"#i-1/10-1i\")", write("(list (number->string 1.5+.25i 2) (number->string 1.+inf.0i 16) (number->string -.5-1.i 2))"));
        assertEquals("#t", write("""
                        (let loop ((zs (list .5 255. -1.125 0. 1e300 5e-324 .1 -1e-10 +inf.0 -inf.0 1.5+.25i -.5-1.i 1.+inf.0i)))
                          (or (null? zs)
                              (and (let each ((radixes '(2 8 10 16)))
                                     (or (null? radixes)
                                         (and (eqv? (car zs) (string->number (number->string (car zs) (car radixes)) (car radixes)))
                                              (each (cdr radixes)))))
                                   (loop (cdr zs)))))"""));
    }

    @Test
    public void signedZerosAndBranchCuts() {
        // M4-12 (Larceny's inexact and complex tests): negation flips a zero's sign, abs clears it.
        assertEquals("(-0.0 0.0 -0.0 0.0 0 -1/2)", write("(list (- 0.0) (- -0.0) (- -0.0 0.0) (abs -0.0) (- 0) (- 1/2))"));
        assertEquals("-0.0-1.0i", write("(- 0.0+1.0i)"));
        // -0.0 is on the negative side of log's branch cut; a negative real's log is complex.
        assertEquals("(-inf.0 #t #t)", write("(let ((z (log -0.0))) (list (real-part z) (= (imag-part z) (acos -1)) (= (log -1) (make-rectangular 0.0 (acos -1)))))"));
        assertEquals("-inf.0", write("(log 0.0)"));
        assertEquals("+nan.0", write("(log +nan.0)"));
        // No rational is within any distance of an infinity: (rationalize +inf.0 +inf.0) is NaN.
        assertEquals("(+nan.0 0.0 +inf.0 +nan.0)", write("(list (rationalize +inf.0 +inf.0) (rationalize 3.0 +inf.0) (rationalize +inf.0 3.0) (rationalize +nan.0 1.0))"));
    }

    /**
     * One fixnum overflow must not cost the fixnum fast path: a primitive's root node is shared by
     * every call that doesn't run the primitive in place (through {@code apply}, {@code map}, a call
     * site that has seen too many procedures), so a specialization excluded by an overflow would slow
     * all of them down (docs/performance-research.md, section 2, item 3). The calls below reach that
     * node through {@code via-root}'s {@code apply}, whose trampoline has run four other primitives
     * in place and so calls the root of any other (plan.md M10-03); a call site's own node (plan.md
     * M5-09) is the same node class.
     */
    private static final String VIA_ROOT = "(define (via-root f xs) (car (list (apply f xs))))" +
                    " (via-root max '(1)) (via-root min '(1)) (via-root abs '(1)) (via-root exact '(1))";

    @Test
    public void fixnumArithmeticKeepsItsFastPathAfterAnOverflow() {
        String[][] cases = {
                        // primitive, an overflowing call, its result, then small calls on the same node
                        {"+", "(apply + '(4611686018427387904 4611686018427387904))", "9223372036854775808", "(apply + '(1 2))", "3"},
                        {"-", "(apply - '(-9223372036854775808 1))", "-9223372036854775809", "(apply - '(7 2))", "5"},
                        {"-", "(apply - '(-9223372036854775808))", "9223372036854775808", "(apply - '(7))", "-7"},
                        {"*", "(apply * '(4294967296 4294967296))", "18446744073709551616", "(apply * '(6 7))", "42"},
                        {"*", "(apply * '(-9223372036854775808 -1))", "9223372036854775808", "(apply * '(-6 7))", "-42"},
        };
        for (String[] c : cases) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (Context context = Context.newBuilder("scheme").out(out).build()) {
                ResultProbe probe = context.getEngine().getInstruments().get(ResultProbe.ID).lookup(ResultProbe.class);
                context.eval("scheme", VIA_ROOT + " (write " + c[1].replace("(apply ", "(via-root ") + ")");
                assertEquals(c[1], c[2], out.toString(StandardCharsets.UTF_8));
                for (int i = 0; i < 3; i++) {
                    Value small = context.eval("scheme", c[3].replace("(apply ", "(via-root "));
                    assertTrue(c[3] + " is a fixnum", small.isNumber() && small.fitsInLong());
                    assertEquals(c[3], Long.parseLong(c[4]), small.asLong());
                }
                context.eval(Source.newBuilder("scheme", c[0], ResultProbe.SOURCE).buildLiteral());
                // Still a fixnum path, now one that handles overflow per call; not the generic path.
                assertSpecializations(c[1], List.of("doLongOverflow"), ((SClosure) probe.last).callTarget.getRootNode());
            }
        }
        // The same at call sites that run the primitive in place, with a node of their own: in tail
        // position (+, -) and not (*).
        String[][] sites = {
                        // the site, an overflowing call, its result, then a small call on the same site
                        {"(define (site a b) (+ a b))", "(site 4611686018427387904 4611686018427387904)", "9223372036854775808", "(site 1 2)", "3"},
                        {"(define (site a) (- a))", "(site -9223372036854775808)", "9223372036854775808", "(site 7)", "-7"},
                        {"(define (site a b) (car (list (* a b))))", "(site 4294967296 4294967296)", "18446744073709551616", "(site 6 7)", "42"},
        };
        for (String[] c : sites) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (Context context = Context.newBuilder("scheme").out(out).build()) {
                ResultProbe probe = context.getEngine().getInstruments().get(ResultProbe.ID).lookup(ResultProbe.class);
                context.eval("scheme", c[0] + " (write " + c[1] + ")");
                assertEquals(c[1], c[2], out.toString(StandardCharsets.UTF_8));
                for (int i = 0; i < 3; i++) {
                    assertEquals(c[3], Long.parseLong(c[4]), context.eval("scheme", c[3]).asLong());
                }
                context.eval(Source.newBuilder("scheme", "site", ResultProbe.SOURCE).buildLiteral());
                assertSpecializations(c[0], List.of("doLongOverflow"), ((SClosure) probe.last).callTarget.getRootNode());
            }
        }
    }

    /**
     * The active specializations of the introspectable nodes under {@code root}, sorted, are
     * {@code expected}. Under a Bytecode DSL root, those are the nodes that its operations cache.
     */
    private static void assertSpecializations(String what, List<String> expected, Node root) {
        List<Node> nodes = new ArrayList<>();
        if (root instanceof BytecodeRootNode bytecodeRoot) {
            for (Instruction instruction : bytecodeRoot.getBytecodeNode().getInstructions()) {
                for (Instruction.Argument argument : instruction.getArguments()) {
                    if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE && argument.asCachedNode() != null) {
                        nodes.addAll(NodeUtil.findAllNodeInstances(argument.asCachedNode(), Node.class));
                    }
                }
            }
        } else {
            nodes.addAll(NodeUtil.findAllNodeInstances(root, Node.class));
        }
        List<String> active = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        for (Node node : nodes) {
            if (Introspection.isIntrospectable(node)) {
                for (SpecializationInfo s : Introspection.getSpecializations(node)) {
                    if (s.isActive()) {
                        active.add(s.getMethodName());
                    }
                    if (s.isExcluded()) {
                        excluded.add(s.getMethodName());
                    }
                }
            }
        }
        active.sort(null);
        assertEquals(what + ": active " + active + ", excluded " + excluded, expected, active);
    }

    /** The overflow checks of the fixnum path that survives an overflow, at the edges of the range. */
    @Test
    public void fixnumOverflowChecksAreExact() {
        long[] edges = {Long.MIN_VALUE, Long.MIN_VALUE + 1, -3037000500L, -3037000499L, -4294967296L, -2, -1, 0, 1, 2, 4294967296L, 3037000499L, 3037000500L, Long.MAX_VALUE - 1, Long.MAX_VALUE};
        StringBuilder program = new StringBuilder("(list");
        StringBuilder expected = new StringBuilder("(");
        for (String op : new String[]{"+", "-", "*"}) {
            for (long a : edges) {
                for (long b : edges) {
                    program.append(" (").append(op).append(' ').append(a).append(' ').append(b).append(')');
                    BigInteger x = BigInteger.valueOf(a);
                    BigInteger y = BigInteger.valueOf(b);
                    BigInteger r = switch (op) {
                        case "+" -> x.add(y);
                        case "-" -> x.subtract(y);
                        default -> x.multiply(y);
                    };
                    expected.append(expected.length() == 1 ? "" : " ").append(r);
                }
            }
        }
        program.append(")");
        expected.append(')');
        // Twice: the first overflow of each operation switches its node to the overflow-checking path.
        assertEquals("(" + expected + " " + expected + ")", write("(list " + program + " " + program + ")"));
    }

    @Test
    public void fixnumResultsStayCanonical() {
        // Overflow promotes to a bignum, never wraps; a result that fits a fixnum is demoted to one.
        String big = "(+ 9223372036854775807 1)";
        String[][] fixnums = {
                        {"(- " + big + " 1)", "9223372036854775807"},
                        {"(- " + big + ")", "-9223372036854775808"},
                        {"(* -1 " + big + ")", "-9223372036854775808"},
                        {"(+ -1 " + big + ")", "9223372036854775807"},
                        {"(- " + big + " " + big + ")", "0"},
                        {"(* 3037000499 3037000499)", "9223372030926249001"},
        };
        try (Context context = Context.create("scheme")) {
            for (String[] c : fixnums) {
                Value v = context.eval("scheme", c[0]);
                assertTrue(c[0] + " is a fixnum", v.isNumber() && v.fitsInLong());
                assertEquals(c[0], Long.parseLong(c[1]), v.asLong());
            }
        }
        assertEquals("(9223372036854775808 -9223372036854775809 9223372037000250000 #t)",
                        write("(list " + big + " (- -9223372036854775808 1) (* 3037000500 3037000500) (exact-integer? " + big + "))"));
    }
}
