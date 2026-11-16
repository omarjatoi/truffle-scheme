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
     * all of them down (docs/performance-research.md, section 2, item 3). The calls below go through
     * {@code apply}, which reaches that node; a call site's own node (plan.md M5-09) is the same
     * node class.
     */
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
                context.eval("scheme", "(write " + c[1] + ")");
                assertEquals(c[1], c[2], out.toString(StandardCharsets.UTF_8));
                for (int i = 0; i < 3; i++) {
                    Value small = context.eval("scheme", c[3]);
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
