package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

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
     * The transcendental functions of flonums are fdlibm's ({@code StrictMath}), on every platform,
     * launcher and tier (design.md D1, "Transcendental functions"): the same digits everywhere,
     * correctly rounded in most cases but not all. {@code (exp 1)} is fdlibm's, one ulp above the
     * nearest flonum to e, 2.718281828459045, as is {@code (log 3)}, one below 1.0986122886681098;
     * {@code java.lang.Math} gives the nearest on x86-64 (its intrinsics) and fdlibm's on AArch64.
     * {@code (sin -1.6361793969095206)} is the nearest, which AArch64's {@code Math.sin} isn't.
     */
    @Test
    public void transcendentalsHaveKnownValues() {
        String[][] cases = {
                        {"(exp 1)", "2.7182818284590455"},
                        {"(exp 0.5)", "1.6487212707001282"},
                        {"(exp -10)", "4.539992976248485e-05"},
                        {"(log 10)", "2.302585092994046"},
                        {"(log 3)", "1.0986122886681096"},
                        {"(log 1000 10)", "2.9999999999999996"},
                        {"(log 8 2)", "3.0"},
                        {"(sin 1e22)", "-0.8522008497671888"},
                        {"(cos 1e22)", "0.523214785395139"},
                        {"(sin -1.6361793969095206)", "-0.9978632884277808"},
                        {"(cos -3.091600611437693)", "-0.9987506580899693"},
                        {"(tan 1)", "1.5574077246549023"},
                        {"(asin 0.5)", "0.5235987755982989"},
                        {"(acos 0.5)", "1.0471975511965979"},
                        {"(atan 1)", "0.7853981633974483"},
                        {"(atan 1 1)", "0.7853981633974483"},
                        {"(atan -3 -4)", "-2.498091544796509"},
                        {"(sqrt 2.)", "1.4142135623730951"},
                        {"(expt 2.5 3.7)", "29.67413253642086"},
                        {"(expt 1.1 100)", "13780.61233982238"},
                        {"(exp +i)", "0.5403023058681398+0.8414709848078965i"},
        };
        for (String[] c : cases) {
            assertEquals(c[0], c[1], write(c[0]));
        }
    }

    /**
     * A sweep of each transcendental function's arguments, with fdlibm's results for them. The calls
     * are data, a quoted list, which {@link #RUN} runs at a few call sites: as code, they would make
     * a procedure too large to compile.
     */
    private static final class Sweep {
        final StringBuilder cases = new StringBuilder("'(");
        final List<String> calls = new ArrayList<>();
        final List<Double> expected = new ArrayList<>();

        Sweep() {
            java.util.Random random = new java.util.Random(20261117);
            for (int i = 0; i < 150; i++) {
                double small = random.nextDouble() * 20 - 10;
                double wide = Math.copySign(Math.exp(random.nextDouble() * 50 - 20), random.nextDouble() - 0.5);
                double positive = Math.exp(random.nextDouble() * 1400 - 700);
                double unit = random.nextDouble() * 2 - 1;
                double y = random.nextDouble() * 40 - 20;
                add("exp", StrictMath.exp(small * 70), small * 70);
                add("log", StrictMath.log(positive), positive);
                add("log", StrictMath.log(positive) / StrictMath.log(-small + 10.5), positive, -small + 10.5);
                add("sin", StrictMath.sin(small), small);
                add("sin", StrictMath.sin(wide), wide);
                add("cos", StrictMath.cos(small), small);
                add("cos", StrictMath.cos(wide), wide);
                add("tan", StrictMath.tan(wide), wide);
                add("asin", StrictMath.asin(unit), unit);
                add("acos", StrictMath.acos(unit), unit);
                add("atan", StrictMath.atan(wide), wide);
                add("atan", StrictMath.atan2(small, wide), small, wide);
                add("sqrt", StrictMath.sqrt(positive), positive);
                add("expt", StrictMath.pow(positive, y / 100), positive, y / 100);
                add("expt", StrictMath.pow(-small + 10.5, y), -small + 10.5, y);
                long k = random.nextInt(81) - 40;
                addCall(StrictMath.pow(small, k), "(expt " + Double.toString(small).replace('E', 'e') + " " + k + ")");
            }
            cases.append(')');
        }

        private void add(String f, double result, double... args) {
            StringBuilder call = new StringBuilder("(").append(f);
            for (double a : args) {
                call.append(' ').append(Double.toString(a).replace('E', 'e'));
            }
            call.append(')');
            addCall(result, call.toString());
        }

        /** A call written out, for one with an exact argument. */
        private void addCall(double result, String call) {
            cases.append(call);
            calls.add(call);
            expected.add(result);
        }

        /** The calls whose results in {@code out}, what {@code (write (sweep cases))} wrote, are wrong. */
        List<String> mismatches(String out) {
            String[] got = out.substring(1, out.length() - 1).split(" ");
            assertEquals(expected.size(), got.length);
            List<String> wrong = new ArrayList<>();
            for (int i = 0; i < got.length; i++) {
                if (Double.doubleToRawLongBits(Double.parseDouble(got[i])) != Double.doubleToRawLongBits(expected.get(i))) {
                    wrong.add(calls.get(i) + " is " + got[i] + ", not " + expected.get(i));
                }
            }
            return wrong;
        }
    }

    /** {@code (sweep cases)}: the result of each call in the list {@code cases}. */
    private static final String RUN = """
                    (define (procedure name)
                      (case name
                        ((exp) exp) ((log) log) ((sin) sin) ((cos) cos) ((tan) tan) ((asin) asin)
                        ((acos) acos) ((atan) atan) ((sqrt) sqrt) ((expt) expt)))
                    (define (call name args)
                      (let ((f (procedure name)))
                        (if (null? (cdr args)) (f (car args)) (f (car args) (cadr args)))))
                    (define (sweep cases) (map (lambda (c) (call (car c) (cdr c))) cases))
                    """;

    @Test
    public void transcendentalsAreFdlibms() {
        Sweep sweep = new Sweep();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("scheme").out(out).build()) {
            context.eval("scheme", RUN + "(write (sweep " + sweep.cases + "))");
        }
        List<String> wrong = sweep.mismatches(out.toString(StandardCharsets.UTF_8));
        assertTrue(wrong.size() + " of " + sweep.expected.size() + ": " + wrong, wrong.isEmpty());
    }

    /**
     * The same sweep interpreted and in code the JIT compiled (at once, in the foreground) gives
     * the same bits: the interpreter's and compiled code's primitives call the same functions.
     */
    @Test
    public void transcendentalsAreTheSameInterpretedAndCompiled() {
        Sweep sweep = new Sweep();
        String program = RUN + "(define cases " + sweep.cases + ") (sweep cases) (sweep cases) (write (sweep cases))";
        String[] outs = new String[2];
        for (int compiled = 0; compiled < 2; compiled++) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Context.Builder builder = Context.newBuilder("scheme").allowExperimentalOptions(true).out(out);
            if (compiled == 0) {
                builder.option("engine.Compilation", "false");
            } else {
                builder.option("engine.Compilation", "true").option("engine.CompileImmediately", "true").option("engine.BackgroundCompilation", "false");
            }
            Context context;
            try {
                context = builder.build();
            } catch (IllegalArgumentException e) {
                org.junit.Assume.assumeNoException("no optimizing Truffle runtime", e);
                throw e;
            }
            try (Context c = context) {
                c.eval("scheme", program);
            }
            outs[compiled] = out.toString(StandardCharsets.UTF_8);
        }
        assertEquals(outs[0], outs[1]);
        assertEquals(List.of(), sweep.mismatches(outs[1]));
    }

    /**
     * What the procedures that branch on a real number's sign (magnitude, log, expt, rationalize,
     * angle, abs, sqrt, make-polar) return for the special flonums and a few exact numbers.
     * Recorded before flonums got their own sign path (#242), which kept every one bit for bit,
     * then corrected where a NaN or -0.0 was taken for a zero, and where a zero power gave a complex
     * or NaN result (#242's follow-up; {@link #nanAndNegativeZeroFollowIeee754AndR7rs} says why),
     * and where -inf.0 to a non-integer power was NaN (#243's follow-up; {@link
     * #exptOfNegativeBasesAndMagnitudeFollowIeee754} says why).
     */
    @Test
    public void signDependentResultsOfSpecialValues() {
        String[][] cases = {
                        {"0.", "(0.0 0.0 #f -inf.0 0.0 0.0 0.0 1.0 1.0 0.0 0.0 0.3333333333333333 0.0 0.0 0.0 2.0+0.0i)"},
                        {"-0.", "(-0.0 0.0 #f -inf.0+3.141592653589793i 0.0 0.0 0.0 1.0 1.0 -0.0 0.0 0.3333333333333333 3.141592653589793 0.0 -0.0 2.0-0.0i)"},
                        {"+nan.0", "(+nan.0 +nan.0 #f +nan.0 +nan.0 +nan.0 +nan.0 1.0 +nan.0 +nan.0 +nan.0 +nan.0 +nan.0 +nan.0 +nan.0 +nan.0+nan.0i)"},
                        {"-nan.0", "(+nan.0 +nan.0 #f +nan.0 +nan.0 +nan.0 +nan.0 1.0 +nan.0 +nan.0 +nan.0 +nan.0 +nan.0 +nan.0 +nan.0 +nan.0+nan.0i)"},
                        {"+inf.0", "(+inf.0 +inf.0 #f +inf.0 +inf.0 +inf.0 0.0 1.0 +inf.0 +inf.0 +inf.0 0.0 0.0 +inf.0 +inf.0 +nan.0+nan.0i)"},
                        {"-inf.0", "(-inf.0 +inf.0 #f +inf.0+3.141592653589793i +inf.0+inf.0i +inf.0+inf.0i 0.0-0.0i 1.0 0.0 -inf.0 -inf.0 0.0 " +
                                        "3.141592653589793 +inf.0 0.0+inf.0i +nan.0+nan.0i)"},
                        {"1.", "(1.0 1.0 #f 0.0 1.0 1.0 1.0 1.0 2.0 1.0 1.0 0.0 0.0 1.0 1.0 1.0806046117362795+1.682941969615793i)"},
                        {"-1.", "(-1.0 1.0 #f 0.0+3.141592653589793i 6.123233995736766e-17+1.0i 3.061616997868383e-16+1.0i 6.123233995736766e-17-1.0i 1.0 0.5 -1.0 " +
                                        "-1.0 0.0 3.141592653589793 1.0 0.0+1.0i 1.0806046117362795-1.682941969615793i)"},
                        {"5e-324", "(5.0e-324 5.0e-324 #f -744.4400719213812 2.2227587494850775e-162 0.0 4.4989137945431964e+161 1.0 1.0 0.0 0.0 0.3333333333333333 0.0 " +
                                        "5.0e-324 2.2227587494850775e-162 2.0+1.0e-323i)"},
                        {"-5e-324", "(-5.0e-324 5.0e-324 #f -744.4400719213812+3.141592653589793i 1.361047193916867e-178+2.222758749485127e-162i 0.0+0.0i " +
                                        "2.754790189063538e+145-4.498913794543097e+161i 1.0 1.0 -0.0 0.0 0.3333333333333333 3.141592653589793 5.0e-324 " +
                                        "0.0+2.2227587494850775e-162i 2.0-1.0e-323i)"},
                        {"1e308", "(1.0e+308 1.0e+308 #f 709.1962086421661 1.0e+154 +inf.0 1.0e-154 1.0 +inf.0 +inf.0 1.0e+308 0.0 0.0 1.0e+308 1.0e+154 " + "-1.782617875374067+0.9067929810032982i)"},
                        {"-1e308", "(-1.0e+308 1.0e+308 #f 709.1962086421661+3.141592653589793i 6.123233995736807e+137+1.0000000000000067e+154i +inf.0+inf.0i " +
                                        "6.123233995736725e-171-9.999999999999932e-155i 1.0 0.0 -inf.0 -1.0e+308 0.0 3.141592653589793 1.0e+308 0.0+1.0e+154i " +
                                        "-1.782617875374067-0.9067929810032982i)"},
                        {"0", "(0 0 #f -inf.0 0.0 0.0 0.0 1.0 1.0 0 0 1/3 0 0 0 2)"},
                        {"-1", "(-1 1 #f 0.0+3.141592653589793i 6.123233995736766e-17+1.0i 3.061616997868383e-16+1.0i 6.123233995736766e-17-1.0i 1.0 0.5 -1 -1 0 " +
                                        "3.141592653589793 1 0+i 1.0806046117362795-1.682941969615793i)"},
                        {"(- (expt 10 30))", "(-1000000000000000000000000000000 1000000000000000000000000000000 #f 69.07755278982137+3.141592653589793i " +
                                        "0.061232339957367585+999999999999998.8i 3.061616997868365e+59+9.999999999999941e+74i 6.123233995736774e-32-1.0000000000000013e-15i " +
                                        "1.0 0.0 -1000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000 " +
                                        "-1000000000000000000000000000000 0 3.141592653589793 1000000000000000000000000000000 0+1000000000000000i " + "-1.999912921791933-0.01866293786235165i)"},
                        {"1/2", "(1/2 1/2 #f -0.6931471805599453 0.7071067811865476 0.17677669529663687 1.414213562373095 1.0 1.4142135623730951 1/8 1/2 0 0 1/2 " +
                                        "0.7071067811865476 1.7551651237807455+0.958851077208406i)"},
                        {"-1/2", "(-1/2 1/2 #f -0.6931471805599453+3.141592653589793i 4.329780281177466e-17+0.7071067811865475i 5.412225351471833e-17+0.17677669529663687i " +
                                        "8.659560562354932e-17-1.414213562373095i 1.0 0.7071067811865475 -1/8 -1/2 0 3.141592653589793 1/2 0.0+0.7071067811865476i " +
                                        "1.7551651237807455-0.958851077208406i)"},
        };
        for (String[] c : cases) {
            assertEquals(c[0], c[1], write("(let ((x " + c[0] + ")) (list x (magnitude x) (eqv? (magnitude x) -0.) (log x) (expt x 0.5) (expt x 2.5) (expt x -0.5) " +
                            "(expt x 0.) (expt 2. x) (expt x 3) (rationalize x 1/10) (rationalize 1/3 x) (angle x) (abs x) (sqrt x) (make-polar 2 x)))"));
        }
        assertEquals("(\"log: not a number: a\" \"expt: not a number: a\" \"magnitude: not a number: a\")", write("(map (lambda (f) (guard (e (#t (error-object-message e))) (f 'a))) " +
                        "(list log (lambda (x) (expt x 0.5)) magnitude))"));
    }

    /**
     * NaN and -0.0 (#242's follow-up), by R7RS §6.2.4: "An arithmetic operation where one operand is
     * NaN returns NaN, unless the implementation can prove that the result would be the same if the
     * NaN were replaced by any rational number"; "A NaN always compares false to any number";
     * "numerical comparisons treat negative zero as equal to zero"; and the transcendental functions
     * follow IEEE 754 (StrictMath's pow: pow(x, ±0) is 1 for every x, a NaN included). A NaN is
     * not a zero: {@code (expt +nan.0 0.5)} was 0.0, since NaN's sign was taken as 0. §6.2.6 (expt):
     * "The value of 0^z is 1 if (zero? z), 0 if (real-part z) is positive, and an error otherwise.
     * Similarly for 0.0^z, with inexact results", where the "otherwise" keeps its unspecified 0
     * (design.md D9) and a NaN power is NaN. The rationale of magnitude: "the same as abs for a real
     * argument", so {@code (magnitude -0.0)} is 0.0. max and min are inexact if any argument is
     * (§6.2.6's note), NaN if any is NaN (§6.2.4), and take +0.0 above -0.0 as IEEE 754-2019's
     * maximum and minimum do. {@code (angle -0.0)} is π, the imaginary part of {@code (log -0.0)}
     * (§6.2.6: "-inf.0+πi"), and atan's table's {@code y = +0.0, x = -0.0}.
     */
    @Test
    public void nanAndNegativeZeroFollowIeee754AndR7rs() {
        String[][] cases = {
                        // expt: a NaN base or power propagates; a zero power is 1
                        {"(expt +nan.0 0.5)", "+nan.0"}, {"(expt +nan.0 2.5)", "+nan.0"}, {"(expt +nan.0 -0.5)", "+nan.0"}, {"(expt -nan.0 1/2)", "+nan.0"},
                        {"(expt +nan.0 3)", "+nan.0"}, {"(expt +nan.0 0)", "1.0"}, {"(expt +nan.0 0.)", "1.0"}, {"(expt +nan.0 -0.)", "1.0"},
                        {"(expt 0. +nan.0)", "+nan.0"}, {"(expt -0. +nan.0)", "+nan.0"}, {"(expt 0 +nan.0)", "+nan.0"}, {"(expt -2. +nan.0)", "+nan.0"},
                        {"(expt -2 +nan.0)", "+nan.0"}, {"(expt 2 +nan.0)", "+nan.0"}, {"(expt 0. (make-rectangular +nan.0 1.))", "+nan.0"},
                        {"(nan? (expt (make-rectangular +nan.0 0.) 2.5))", "#t"}, {"(nan? (expt (make-rectangular 0. +nan.0) 2))", "#t"},
                        {"(nan? (expt (make-rectangular +nan.0 +nan.0) 1/2))", "#t"},
                        {"(expt -inf.0 0.)", "1.0"}, {"(expt -1. 0.)", "1.0"}, {"(expt -1 -0.)", "1.0"}, {"(expt -1/2 0.)", "1.0"}, {"(expt +inf.0 0.)", "1.0"},
                        // expt of a zero base: as before, but 0.0^z is 1.0 for a zero complex z too
                        {"(expt 0. 0.5)", "0.0"}, {"(expt -0. 0.5)", "0.0"}, {"(expt 0. -0.5)", "0.0"}, {"(expt 0 -0.5)", "0.0"}, {"(expt 0. 0)", "1.0"},
                        {"(expt 0 0.)", "1.0"}, {"(expt -0. -0.)", "1.0"}, {"(expt 0 0)", "1"}, {"(expt 0 1/2)", "0"}, {"(expt -0. 3)", "-0.0"}, {"(expt 0. 1+i)", "0.0"},
                        {"(expt 0 1+i)", "0"}, {"(expt (make-rectangular 0. 0.) 0)", "1.0"}, {"(expt (make-rectangular 0. -0.) 2)", "0.0"},
                        // predicates and comparisons
                        {"(positive? +nan.0)", "#f"}, {"(negative? +nan.0)", "#f"}, {"(zero? +nan.0)", "#f"}, {"(zero? -0.0)", "#t"}, {"(positive? -0.0)", "#f"},
                        {"(negative? -0.0)", "#f"}, {"(zero? (make-rectangular -0. -0.))", "#t"}, {"(= -0.0 0 0.0)", "#t"}, {"(< -0.0 0.0)", "#f"}, {"(< 1 +nan.0)", "#f"},
                        {"(= +nan.0 +nan.0)", "#f"}, {"(>= +nan.0 +nan.0)", "#f"},
                        // max and min
                        {"(max 1 +nan.0)", "+nan.0"}, {"(max +nan.0 1)", "+nan.0"}, {"(min 1/2 +nan.0)", "+nan.0"}, {"(max 3 4.)", "4.0"},
                        {"(max -0.0 0.0)", "0.0"}, {"(max 0.0 -0.0)", "0.0"}, {"(min 0.0 -0.0)", "-0.0"}, {"(min -0.0 0.0)", "-0.0"}, {"(max -0.0 0)", "0.0"},
                        {"(min 0 -0.0)", "-0.0"}, {"(max -0. -0.)", "-0.0"}, {"(min 0. 0)", "0.0"}, {"(max -1 -0.)", "-0.0"}, {"(min 1 0 -0. 2.)", "-0.0"},
                        // exact
                        {"(exact +nan.0)", "\"exact: not a finite number: +nan.0\""}, {"(inexact->exact -nan.0)", "\"exact: not a finite number: +nan.0\""},
                        {"(exact -0.0)", "0"},
                        // magnitude, abs, angle
                        {"(magnitude -0.0)", "0.0"}, {"(magnitude +nan.0)", "+nan.0"}, {"(abs -0.0)", "0.0"}, {"(abs +nan.0)", "+nan.0"},
                        {"(angle -0.0)", "3.141592653589793"}, {"(angle +nan.0)", "+nan.0"}, {"(angle 0.0)", "0.0"}, {"(angle 0)", "0"},
                        // log, sqrt, exact-integer-sqrt
                        {"(log -0.0)", "-inf.0+3.141592653589793i"}, {"(log 0.0)", "-inf.0"}, {"(log +nan.0)", "+nan.0"}, {"(log -nan.0)", "+nan.0"},
                        {"(log +nan.0 2)", "+nan.0"}, {"(sqrt -0.0)", "-0.0"}, {"(sqrt +nan.0)", "+nan.0"},
                        {"(exact-integer-sqrt +nan.0)", "\"exact-integer-sqrt: not a non-negative exact integer: +nan.0\""},
                        // rounding, rationalize, make-polar
                        {"(round -0.4)", "-0.0"}, {"(truncate -0.5)", "-0.0"}, {"(ceiling -0.5)", "-0.0"}, {"(round -0.0)", "-0.0"}, {"(floor +nan.0)", "+nan.0"},
                        {"(rationalize +nan.0 1)", "+nan.0"}, {"(rationalize 1 +nan.0)", "+nan.0"}, {"(make-polar +nan.0 0)", "+nan.0"},
                        {"(make-polar 2 +nan.0)", "+nan.0+nan.0i"},
                        // printing
                        {"(number->string -0.0)", "\"-0.0\""}, {"(number->string -nan.0)", "\"+nan.0\""}, {"(number->string -0.0 2)", "\"#i-0\""},
                        {"(number->string +nan.0 16)", "\"+nan.0\""},
        };
        assertWrites(cases);
    }

    /**
     * #243's follow-ups. (1) A real base to an integer-valued flonum power is real: IEEE 754's
     * pow, as R7RS §6.2.3 recommends for transcendental functions ("particularly with respect to
     * infinities and NaNs"), and the value of §6.2.6's e^(z2 log z1), whose imaginary part is
     * sin(nπ)·|z1|^n = 0 for an integer n; through log in floating point it was 4.0-9.8e-16i. An
     * infinite power counts as one: every flonum beyond 2^53 is an even integer, so the power's
     * limit is pow's (|z1|^±∞: 0, 1 or +∞; IEEE 754-2008's pow(±1, ±∞) is 1, where Java's, C89's,
     * is NaN). Only a negative base to a finite non-integer power is
     * complex, and an exact base to an exact power stays exact (erratum 21). (2) A negative
     * infinite base to a non-integer power: e^(z2 log z1), with z2 real multiplying each part of
     * log z1 = +∞+πi, so no 0·∞ (C99 Annex G.5.1's real times complex): (expt -inf.0 0.5) is
     * +inf.0+inf.0i, the limit of (expt -1e308 0.5) = 6.1e137+1e154i, not +nan.0+nan.0i. {@code (*
     * 0 +inf.0)} stays NaN: §6.2.2 lets an exact 0 times an inexact number be exact 0 or the
     * inexact product, and ours is the inexact product, as for every other operation. (3) The
     * magnitude of a complex number is StrictMath's hypot: no overflow or underflow of the squares,
     * and IEEE 754's hypot(±∞, NaN) = +∞; an exact one is exact when its square is a perfect square.
     */
    @Test
    public void exptOfNegativeBasesAndMagnitudeFollowIeee754() {
        String[][] cases = {
                        // a negative real base to an integer-valued flonum power: real
                        {"(expt -2. 2.)", "4.0"}, {"(expt -2. 3.)", "-8.0"}, {"(expt -2 2.)", "4.0"}, {"(expt -2. -2.)", "0.25"}, {"(expt -2. -3.)", "-0.125"},
                        {"(expt -1/2 3.)", "-0.125"}, {"(expt -3 -1.)", "-0.3333333333333333"}, {"(expt -1e-200 3.)", "-0.0"}, {"(expt -1e308 2.)", "+inf.0"},
                        {"(expt -1. 1e300)", "1.0"}, {"(expt -2. 1e300)", "+inf.0"}, {"(expt -.5 1e300)", "0.0"}, {"(expt (- (expt 10 30)) 2.)", "1.0000000000000001e+60"},
                        // ... and to an infinite power: pow's limits
                        {"(expt -2. +inf.0)", "+inf.0"}, {"(expt -.5 +inf.0)", "0.0"}, {"(expt -1. +inf.0)", "1.0"}, {"(expt -2. -inf.0)", "0.0"},
                        {"(expt -.5 -inf.0)", "+inf.0"}, {"(expt -1 -inf.0)", "1.0"}, {"(expt -inf.0 +inf.0)", "+inf.0"}, {"(expt -inf.0 -inf.0)", "0.0"},
                        // ... where IEEE 754-2008's pow(±1, ±∞) is 1, Java's NaN (C89's)
                        {"(expt 1. +inf.0)", "1.0"}, {"(expt 1 -inf.0)", "1.0"},
                        // ... and pow(+1, y) is 1 for every y, a NaN too (M11-01; Java's is NaN), but not pow(-1, NaN)
                        {"(expt 1. +nan.0)", "1.0"}, {"(expt 1 +nan.0)", "1.0"}, {"(expt 1. -nan.0)", "1.0"}, {"(expt 1/1 +nan.0)", "1.0"},
                        {"(expt -1. +nan.0)", "+nan.0"}, {"(expt -1 +nan.0)", "+nan.0"}, {"(expt 1.0000000000000002 +nan.0)", "+nan.0"},
                        // a negative infinite base to an integer-valued power
                        {"(expt -inf.0 2.)", "+inf.0"}, {"(expt -inf.0 3.)", "-inf.0"}, {"(expt -inf.0 -2.)", "0.0"}, {"(expt -inf.0 -3.)", "-0.0"},
                        {"(expt -inf.0 3)", "-inf.0"},
                        // ... and to a non-integer power: e^(z2 log z1), each part of log z1 times z2
                        {"(expt -inf.0 0.5)", "+inf.0+inf.0i"}, {"(expt -inf.0 1.5)", "-inf.0-inf.0i"}, {"(expt -inf.0 0.75)", "-inf.0+inf.0i"},
                        {"(expt -inf.0 2.5)", "+inf.0+inf.0i"}, {"(expt -inf.0 1/3)", "+inf.0+inf.0i"}, {"(expt -inf.0 -0.5)", "0.0-0.0i"},
                        {"(expt -inf.0 -1/3)", "0.0-0.0i"},
                        // a negative finite base to a non-integer power: complex, as before
                        {"(expt -2. 0.5)", "8.659560562354932e-17+1.414213562373095i"}, {"(expt -8. 1/3)", "1.0+1.732050807568877i"},
                        {"(expt -1e308 0.5)", "6.123233995736807e+137+1.0000000000000067e+154i"}, {"(expt -8 1/3)", "1.0+1.732050807568877i"},
                        // exact stays exact
                        {"(expt -2 3)", "-8"}, {"(expt -2 -3)", "-1/8"}, {"(expt -1/2 3)", "-1/8"}, {"(expt -2. 3)", "-8.0"},
                        // an exact 0 times an inexact number is the inexact product (R7RS §6.2.2 allows 0 too)
                        {"(* 0 +inf.0)", "+nan.0"}, {"(* 0 1.5)", "0.0"}, {"(* 0 -1.5)", "-0.0"}, {"(* +nan.0 0)", "+nan.0"},
                        // magnitude: hypot, without overflow or underflow
                        {"(magnitude 1e300+1e300i)", "1.4142135623730952e+300"}, {"(magnitude -1e300-1e300i)", "1.4142135623730952e+300"},
                        {"(magnitude 1e-200+1e-200i)", "1.414213562373095e-200"}, {"(magnitude (make-rectangular 5e-324 5e-324))", "5.0e-324"},
                        {"(magnitude (make-rectangular 1e308 -1e308))", "1.4142135623730951e+308"},
                        {"(magnitude (make-rectangular (expt 10 200) (expt 10 200)))", "1.414213562373095e+200"},
                        {"(magnitude (make-rectangular (expt 10 200) 1))", "1.0e+200"}, {"(magnitude (make-rectangular (expt 10 400) 1))", "+inf.0"},
                        {"(magnitude (make-rectangular (/ (expt 10 200)) (/ (expt 10 200))))", "1.414213562373095e-200"},
                        // ... IEEE 754's hypot: an infinite part makes it +inf.0, a NaN part otherwise NaN
                        {"(magnitude (make-rectangular +inf.0 +nan.0))", "+inf.0"}, {"(magnitude (make-rectangular +nan.0 -inf.0))", "+inf.0"},
                        {"(magnitude (make-rectangular -inf.0 1.))", "+inf.0"}, {"(magnitude (make-rectangular +nan.0 1.))", "+nan.0"},
                        {"(magnitude (make-rectangular 0. +nan.0))", "+nan.0"},
                        // ... exact when it can be, and log's real part is the log of the magnitude
                        {"(magnitude 3+4i)", "5"}, {"(magnitude -3/5+4/5i)", "1"}, {"(magnitude (make-rectangular (* 3 (expt 10 200)) (* 4 (expt 10 200))))", "5" + "0".repeat(200)},
                        {"(magnitude 1+2i)", "2.23606797749979"}, {"(magnitude 3.+4.i)", "5.0"}, {"(magnitude 1.5+2.5i)", "2.9154759474226504"},
                        {"(magnitude (make-rectangular 0. -0.))", "0.0"},
                        {"(let ((z 1e300+1e300i)) (= (real-part (log z)) (log (magnitude z))))", "#t"},
        };
        assertWrites(cases);
    }

    /**
     * M11-01 (#244's follow-ups). A real and a complex number: C99 Annex G.5.1's table, each part
     * on its own, x(u+iv) = xu + i(xv), (u+iv)/x = u/x + i(v/x), x ± (u+iv) = (x ± u) + i(±v),
     * and x/(u+iv) = x(u-iv)/(u²+v²), never the real promoted to x+0i, whose 0·∞ made {@code (* 2.
     * (make-rectangular +inf.0 1.))} +inf.0+nan.0i and whose 0 + -0.0 made {@code (+ 1.
     * (make-rectangular 1. -0.))}'s imaginary part 0.0. An exact real times an inexact part is the
     * inexact product (R7RS §6.2.2 allows exact 0 too; #244). Mixing exactness makes both parts
     * inexact (design.md D1). The expected values and their signs of zero are IEEE 754's
     * round-to-nearest results of those formulas.
     */
    @Test
    public void realAndComplexArithmeticTakeEachPart() {
        String[][] cases = {
                        // * : each part times the real, also an infinite one
                        {"(* 2. (make-rectangular +inf.0 1.))", "+inf.0+2.0i"}, {"(* (make-rectangular +inf.0 1.) 2.)", "+inf.0+2.0i"},
                        {"(* 2 (make-rectangular +inf.0 1.))", "+inf.0+2.0i"}, {"(* 2. (make-rectangular 1. -inf.0))", "2.0-inf.0i"},
                        {"(* +inf.0 (make-rectangular 1. 2.))", "+inf.0+inf.0i"}, {"(* 1e200 (make-rectangular 1e200 1.))", "+inf.0+1.0e+200i"},
                        {"(* +nan.0 (make-rectangular 1. 2.))", "+nan.0+nan.0i"}, {"(* 1.5 1+2i)", "1.5+3.0i"}, {"(* 1/2 1+2i)", "1/2+i"},
                        // ... with its signs of zero
                        {"(* 2. (make-rectangular 1. -0.))", "2.0-0.0i"}, {"(* -2. (make-rectangular 1. 0.))", "-2.0-0.0i"},
                        {"(* -2. (make-rectangular -0. 1.))", "0.0-2.0i"}, {"(* 0 (make-rectangular 1.5 -2.5))", "0.0-0.0i"},
                        {"(* -0. (make-rectangular 1. 2.))", "-0.0-0.0i"},
                        // / by a real: each part divided, without squaring the divisor
                        {"(/ (make-rectangular +inf.0 1.) 2.)", "+inf.0+0.5i"}, {"(/ (make-rectangular 1. 2.) 1e200)", "1.0e-200+2.0e-200i"},
                        {"(/ (make-rectangular 3. 4.) 0.)", "+inf.0+inf.0i"}, {"(/ (make-rectangular 3. -4.) -0.)", "-inf.0+inf.0i"},
                        {"(/ (make-rectangular 1. -0.) 2.)", "0.5-0.0i"}, {"(/ 1+2i 2)", "1/2+i"},
                        // a real divided by a complex number: the conjugate's parts times the real
                        {"(/ 1. (make-rectangular 1. 0.))", "1.0-0.0i"}, {"(/ 1. (make-rectangular 2. -0.))", "0.5+0.0i"},
                        {"(/ 2. (make-rectangular 0. 1.))", "0.0-2.0i"}, {"(/ -2. (make-rectangular 0. 1.))", "-0.0+2.0i"}, {"(/ 2 1+i)", "1-i"},
                        {"(/ 1 1+2i)", "1/5-2/5i"}, {"(/ 1. 3.+4.i)", "0.12-0.16i"},
                        // + and -: the complex number's imaginary part, kept or negated
                        {"(+ 1. (make-rectangular 1. -0.))", "2.0-0.0i"}, {"(+ (make-rectangular 1. -0.) 1.)", "2.0-0.0i"},
                        {"(+ 1 (make-rectangular 1. -0.))", "2.0-0.0i"}, {"(+ +nan.0 (make-rectangular 1. -0.))", "+nan.0-0.0i"},
                        {"(- 1. (make-rectangular 1. -0.))", "0.0+0.0i"}, {"(- 1. (make-rectangular 1. 0.))", "0.0-0.0i"},
                        {"(- (make-rectangular 1. -0.) 1.)", "0.0-0.0i"}, {"(- (make-rectangular 1. 0.) 1)", "0.0+0.0i"},
                        {"(- 0 (make-rectangular 1. 0.))", "-1.0-0.0i"}, {"(- 0. (make-rectangular 0. -0.))", "0.0+0.0i"},
                        {"(+ 1.5 1+2i)", "2.5+2.0i"}, {"(- 1/2 1+2i)", "-1/2-2i"}, {"(- 1.5 1+2i)", "0.5-2.0i"},
        };
        assertWrites(cases);
    }

    /**
     * M11-01 (#244's follow-up). The square root of an exact non-square is the flonum nearest to
     * it, by integer arithmetic (as {@code inexact} of an exact number is, design.md D1): {@code
     * Math.sqrt(flo(z))} overflowed to +inf.0 for a z beyond the flonums' range whose root is in it,
     * underflowed to 0.0 for a tiny one, and rounded twice (z to a flonum, then its root) for a
     * big integer or a rational, an ulp off now and then. An exact square stays exact.
     */
    @Test
    public void sqrtOfAnExactNumberIsTheNearestFlonum() {
        String[][] cases = {
                        // beyond the flonums' range, with a root inside it
                        {"(sqrt (* 2 (expt 10 400)))", "1.414213562373095e+200"}, {"(sqrt (expt 10 401))", "3.1622776601683794e+200"},
                        {"(sqrt (+ (expt 10 400) 1))", "1.0e+200"}, {"(sqrt (/ (* 2 (expt 10 400)) 3))", "8.16496580927726e+199"},
                        {"(sqrt (/ (+ (expt 10 700) 1) (expt 10 300)))", "1.0e+200"}, {"(sqrt (- (* 2 (expt 10 400))))", "0.0+1.414213562373095e+200i"},
                        {"(sqrt (expt 2 2047))", "1.2711610061536464e+308"},
                        // ... and with a root beyond it: +inf.0
                        {"(sqrt (- (expt 2 2048) 1))", "+inf.0"}, {"(sqrt (* 3 (expt 2 2100)))", "+inf.0"},
                        // tiny: a normal or a subnormal root, or 0.0
                        {"(sqrt (/ 2 (expt 10 400)))", "1.414213562373095e-200"}, {"(sqrt (/ 3 (expt 10 640)))", "1.732e-320"},
                        {"(sqrt (/ 2 (expt 10 640)))", "1.414e-320"}, {"(sqrt (/ (expt 2 2149)))", "5.0e-324"}, {"(sqrt (/ 3 (expt 2 2150)))", "5.0e-324"},
                        {"(sqrt (/ 3 (expt 2 2200)))", "0.0"}, {"(sqrt (- (/ 2 (expt 10 400))))", "0.0+1.414213562373095e-200i"},
                        // within the range: rounded once
                        {"(sqrt 607022712050045950413189)", "779116622881.354"}, {"(sqrt 719831/227121)", "1.7802730894245995"},
                        {"(sqrt (- (expt 2 1024) 1))", "1.3407807929942597e+154"}, {"(sqrt 2)", "1.4142135623730951"}, {"(sqrt 1/3)", "0.5773502691896257"},
                        {"(sqrt -2)", "0.0+1.4142135623730951i"}, {"(sqrt 9007199254740993)", "94906265.62425156"},
                        // exact squares stay exact
                        {"(sqrt (expt 10 400))", "1" + "0".repeat(200)}, {"(sqrt (/ 9 (expt 10 400)))", "3/1" + "0".repeat(200)}, {"(sqrt -4/9)", "0+2/3i"},
        };
        assertWrites(cases);
    }

    /**
     * M12-01 (#249's follow-up). A division by an inexact complex number is C99 Annex G.5.1's
     * {@code _Cdivd} (its EXAMPLE 2): the divisor's parts are scaled by a power of 2 (exactly)
     * before they are squared, so a quotient whose parts are finite stays finite and nonzero where
     * the squares overflow or underflow, and a NaN+NaN i result is recomputed from the infinity
     * properties of G.5.1 paragraph 4: a finite number over an infinity is a zero, an infinity over
     * a finite number an infinity, a nonzero number over a zero an infinity (G.3: a complex value
     * with an infinite part is an infinity, even if the other part is a NaN). A real dividend is
     * the real times the divisor's conjugate (M11-01), so {@code (/ 1. (make-rectangular +inf.0
     * 1.))} is 0.0-0.0i, as 1/z is conj(z)/|z|²; it was +nan.0-0.0i. The finite expected values
     * are the correctly rounded quotients (exact rational arithmetic), but for (1e-300+1e-300i) /
     * (3e-300+4e-300i), whose correctly rounded quotient is 0.27999999999999997-0.039999999999999994i:
     * the textbook formula rounds 4 times, and bc - ad cancels (it was +nan.0+nan.0i).
     */
    @Test
    public void complexDivisionScalesTheDivisorAndRecoversInfinities() {
        String[][] cases = {
                        // squares that overflow or underflow: the quotient keeps its finite parts
                        {"(/ 1. (make-rectangular 1e200 1e200))", "5.0e-201-5.0e-201i"}, {"(/ (make-rectangular 1. 1.) (make-rectangular 1e200 1e200))", "1.0e-200+0.0i"},
                        {"(/ (make-rectangular 1e300 1e300) (make-rectangular 1e300 1e300))", "1.0+0.0i"},
                        {"(/ (make-rectangular 1e-300 1e-300) (make-rectangular 1e-300 1e-300))", "1.0+0.0i"},
                        {"(/ 1. (make-rectangular 1e-200 1e-200))", "5.0e+199-5.0e+199i"}, {"(/ (make-rectangular 1e-300 1e-300) (make-rectangular 3e-300 4e-300))",
                                        "0.28-0.03999999999999997i"},
                        {"(/ 1e300 (make-rectangular 3e300 4e300))", "0.12-0.16i"},
                        {"(/ (make-rectangular 1e300 1e300) (make-rectangular 3e300 4e300))", "0.28-0.04i"}, {"(/ 1 (make-rectangular 1e200 1e200))", "5.0e-201-5.0e-201i"},
                        {"(/ 1. (make-rectangular 5e-324 0.))", "+inf.0-0.0i"}, {"(/ (make-rectangular 1. 2.) (make-rectangular 3e-320 4e-320))", "+inf.0+inf.0i"},
                        // a finite number over an infinity is a zero
                        {"(/ 1. (make-rectangular +inf.0 1.))", "0.0-0.0i"}, {"(/ 1. (make-rectangular 1. -inf.0))", "0.0+0.0i"},
                        {"(/ (make-rectangular 1. 1.) (make-rectangular +inf.0 1.))", "0.0+0.0i"}, {"(/ (make-rectangular 1. -1.) (make-rectangular +inf.0 1.))", "0.0-0.0i"},
                        {"(/ (make-rectangular 1. 1.) (make-rectangular +inf.0 +inf.0))", "0.0+0.0i"}, {"(/ (make-rectangular -1. 2.) (make-rectangular 3. -inf.0))", "-0.0-0.0i"},
                        // an infinity over a finite number, and a nonzero number over a zero, are infinities
                        {"(/ (make-rectangular +inf.0 +inf.0) (make-rectangular 1. 0.))", "+inf.0+inf.0i"},
                        {"(/ (make-rectangular +inf.0 -inf.0) (make-rectangular 0. 1.))", "-inf.0-inf.0i"},
                        {"(/ (make-rectangular +inf.0 1.) (make-rectangular 1. 1.))", "+inf.0-inf.0i"}, {"(/ (make-rectangular 1. 1.) (make-rectangular 0. 0.))", "+inf.0+inf.0i"},
                        {"(/ (make-rectangular 1. 1.) (make-rectangular -0. 0.))", "-inf.0-inf.0i"}, {"(/ 1. (make-rectangular 0. 0.))", "+inf.0+nan.0i"},
                        // ... a NaN part with an infinite one too, but a NaN otherwise stays NaN
                        {"(/ 1. (make-rectangular +nan.0 +inf.0))", "0.0-0.0i"}, {"(/ (make-rectangular +nan.0 1.) (make-rectangular 1. 1.))", "+nan.0+nan.0i"},
                        // ordinary quotients: the same bits as the textbook formula
                        {"(/ 3.+4.i 1.+2.i)", "2.2-0.4i"}, {"(/ 1. 3.+4.i)", "0.12-0.16i"}, {"(/ 1. (make-rectangular 1. 0.))", "1.0-0.0i"},
                        {"(/ 2. (make-rectangular 0. 1.))", "0.0-2.0i"}, {"(/ 1+2i 3.+4.i)", "0.44+0.08i"}, {"(/ 1+2i 3+4i)", "11/25+2/25i"},
        };
        assertWrites(cases);
    }

    /**
     * M12-01 (#249's follow-up). The sign of a zero part on a branch cut. R7RS §6.2.6 makes log's
     * imaginary part lie "from −π (inclusive if -0.0 is distinguished, exclusive otherwise) to π
     * (inclusive)", so log, and atan, which R7RS defines through log alone, take the side the zero's
     * sign gives, as C99 §7.3.3 has it: "the sign of zero distinguishes one side of a cut from
     * another". But R7RS's sqrt has "either a positive real part, or a zero real part and a
     * non-negative imaginary part" (and the chibi suite tests {@code (sqrt -1.0-0.0i)} =
     * 0.0+1.0i), where C99's csqrt gives -0.0i's side the negative imaginary axis; so on sqrt's
     * cut either zero gives the positive imaginary axis, and on asin's and acos's, which R7RS
     * defines through that sqrt, either zero gives the real number's value. Off those cuts each
     * function has Annex G.6's conjugate symmetry, f(conj z) = conj f(z), bit for bit with the signs
     * of zeros, and asin and atan are odd (casin(z) = −i casinh(iz), catan(z) = −i catanh(iz)),
     * which R7RS leaves open. A real argument is on the side R7RS's formulas give it, as before.
     */
    @Test
    public void branchCutsAndTheSignOfAZero() {
        String[][] cases = {
                        // sqrt: the negative real axis, the positive imaginary axis from either side (R7RS)
                        {"(sqrt (make-rectangular -4. -0.))", "0.0+2.0i"}, {"(sqrt (make-rectangular -4. 0.))", "0.0+2.0i"}, {"(sqrt -4.)", "0.0+2.0i"},
                        {"(sqrt (make-rectangular -1e-300 -0.))", "0.0+1.0e-150i"}, {"(sqrt (make-rectangular -1. -0.))", "0.0+1.0i"},
                        // ... and off it, conjugate symmetry
                        {"(sqrt (make-rectangular 4. -0.))", "2.0-0.0i"}, {"(sqrt (make-rectangular -0. -0.))", "0.0-0.0i"}, {"(sqrt 3.-4.i)", "2.0-1.0i"},
                        // log: the negative real axis, -π below and π above
                        {"(log (make-rectangular -4. -0.))", "1.3862943611198906-3.141592653589793i"}, {"(log (make-rectangular -4. 0.))", "1.3862943611198906+3.141592653589793i"},
                        {"(log (make-rectangular 4. -0.))", "1.3862943611198906-0.0i"}, {"(log (make-rectangular -0. -0.))", "-inf.0-3.141592653589793i"},
                        // asin and acos: the real axis beyond ±1, the real number's value from either side
                        {"(asin (make-rectangular 2. 0.))", "1.5707963267948966-1.3169578969248166i"}, {"(asin (make-rectangular 2. -0.))", "1.5707963267948966-1.3169578969248166i"},
                        {"(asin (make-rectangular -2. 0.))", "-1.5707963267948966+1.3169578969248166i"}, {"(asin (make-rectangular -2. -0.))", "-1.5707963267948966+1.3169578969248166i"},
                        {"(asin 2.)", "1.5707963267948966-1.3169578969248166i"}, {"(asin -2.)", "-1.5707963267948966+1.3169578969248166i"},
                        {"(acos (make-rectangular 2. 0.))", "0.0+1.3169578969248166i"}, {"(acos (make-rectangular -2. -0.))", "3.141592653589793-1.3169578969248166i"},
                        {"(acos 2.)", "0.0+1.3169578969248166i"}, {"(acos -2.)", "3.141592653589793-1.3169578969248166i"},
                        // ... and off it, conjugate symmetry and oddness (Kahan's asin: the nearest real part is ...521)
                        {"(asin 2.+1.i)", "1.063440023577752+1.4693517443681852i"}, {"(asin 2.-1.i)", "1.063440023577752-1.4693517443681852i"},
                        {"(asin -2.+1.i)", "-1.063440023577752+1.4693517443681852i"}, {"(acos 2.-1.i)", "0.5073563032171445+1.4693517443681852i"},

                        // atan: the imaginary axis beyond ±i, where the real part's zero tells the side
                        {"(atan (make-rectangular 0. 2.))", "1.5707963267948966+0.5493061443340549i"}, {"(atan (make-rectangular -0. 2.))", "-1.5707963267948966+0.5493061443340549i"},
                        {"(atan (make-rectangular 0. -2.))", "1.5707963267948966-0.5493061443340549i"}, {"(atan (make-rectangular -0. -2.))", "-1.5707963267948966-0.5493061443340549i"},
                        {"(atan +2.i)", "1.5707963267948966+0.5493061443340549i"},
                        // ... and off it, conjugate symmetry with the signs of zeros
                        {"(atan (make-rectangular 1. 0.))", "0.7853981633974483+0.0i"}, {"(atan (make-rectangular 1. -0.))", "0.7853981633974483-0.0i"},
        };
        assertWrites(cases);
        // f(conj z) = conj f(z) and, for the odd ones, f(-z) = -f(z), bit for bit, on a grid that
        // crosses every cut, signed zeros and infinities included; on sqrt's, asin's and acos's cuts
        // (a zero imaginary part), f(conj z) = f(z) instead
        assertEquals("()", write("""
                        (let ((parts '(-inf.0 -1e300 -2. -1. -0.5 -1e-300 -0. 0. 1e-300 0.5 1. 2. 1e300 +inf.0))
                              (conj (lambda (z) (make-rectangular (real-part z) (- (imag-part z)))))
                              (same? (lambda (a b) (and (eqv? (real-part a) (real-part b)) (eqv? (imag-part a) (imag-part b)))))
                              (bad '()))
                          (for-each (lambda (x)
                                      (for-each (lambda (y)
                                                  (let ((z (make-rectangular x y)))
                                                    (for-each (lambda (f name odd cut?)
                                                                (let ((w (f z)))
                                                                  (unless (or (nan? w) (same? (f (conj z)) (if (cut? x y) w (conj w))))
                                                                    (set! bad (cons (list name z 'conj) bad)))
                                                                  (unless (or (not odd) (nan? w) (same? (f (- z)) (- w)))
                                                                    (set! bad (cons (list name z 'odd) bad)))))
                                                              (list sqrt log asin acos atan) '(sqrt log asin acos atan) '(#f #f #t #f #t)
                                                              (list (lambda (x y) (and (zero? y) (< x 0))) (lambda (x y) #f)
                                                                    (lambda (x y) (and (zero? y) (> (abs x) 1))) (lambda (x y) (and (zero? y) (> (abs x) 1)))
                                                                    (lambda (x y) #f)))))
                                                parts))
                                    parts)
                          bad)"""));
    }

    /**
     * M13-02 (#280's follow-up). {@code _Cdivd} scales the divisor only, so the dividend's products
     * with it could still overflow, or be subnormal and lose bits that the scaling back then shows.
     * The dividend is scaled too, by a power of 2 (exactly), when its larger part is outside
     * [2^-969, 2^1021): a quotient whose parts are finite comes out finite, and from full-precision
     * products. Inside that range nothing changes, so a part much smaller than the other keeps its
     * bits. The quotients are then the textbook formula's on full-precision products, an ulp from
     * the correctly rounded ones (mpmath), which the comments give, where its roundings make them so.
     */
    @Test
    public void complexDivisionScalesTheDividendToo() {
        String[][] cases = {
                        // products that overflowed: were +inf.0+0.0i, 0.0+inf.0i and +inf.0-inf.0i
                        {"(/ (make-rectangular 1e308 1e308) 1.+1.i)", "1.0e+308+0.0i"}, {"(/ (make-rectangular -1e308 1e308) 1.+1.i)", "0.0+1.0e+308i"},
                        {"(/ 1.7e308 (make-rectangular 0.75 0.75))", "1.1333333333333332e+308-1.1333333333333332e+308i"}, // the nearest: ...334e+308
                        // subnormal products: were 0.28000000000000114-0.04000000000000625i and 0.12000000000000455-0.1599999999999966i
                        {"(/ (make-rectangular 1e-310 1e-310) (make-rectangular 3e-310 4e-310))", "0.27999999999999997-0.04i"}, // the nearest: 0.28-0.04i
                        {"(/ 1e-310 (make-rectangular 3e-310 4e-310))", "0.11999999999999998-0.15999999999999998i"}, // the nearest: 0.12-0.16i
                        // a part much smaller than the other keeps its bits, and a quotient beyond the range is an infinity
                        {"(/ (make-rectangular 1e308 0.3) (make-rectangular 1. 0.))", "1.0e+308+0.3i"},
                        {"(/ (make-rectangular 1e308 1e308) (make-rectangular 0.5 0.))", "+inf.0+inf.0i"},
        };
        assertWrites(cases);
    }

    /**
     * M13-02 (#280's follow-up). R7RS §6.2.6 defines the complex sqrt, exp, log, asin, acos and atan
     * through formulas and is silent on infinities and NaNs (§6.2.3 recommends IEEE 754's
     * functions, "particularly with respect to infinities and NaNs"), so they give C99 Annex G.6's
     * special values: csqrt (G.6.4.2), cexp (G.6.3.1), clog (G.6.3.2), cacos (G.6.1.1), and
     * casin(z) = -i casinh(iz) (G.6.2.2) and catan(z) = -i catanh(iz) (G.6.2.3), with G.6's
     * conjugate symmetry and oddness. Where G.6 leaves a sign unspecified, ours is +. Where R7RS
     * speaks, it wins: on sqrt's cut either zero gives "a zero real part and a non-negative
     * imaginary part", and on asin's and acos's cuts, the real axis beyond ±1, either zero gives the
     * real number's value (M12-01), infinities included.
     */
    @Test
    public void specialValuesOfTheElementaryFunctions() {
        String[][] cases = {
                        // csqrt: was +nan.0+inf.0i, +inf.0+nan.0i, +inf.0+nan.0i, +nan.0+inf.0i
                        {"(sqrt (make-rectangular -inf.0 1.))", "0.0+inf.0i"}, {"(sqrt (make-rectangular -inf.0 -1.))", "0.0-inf.0i"},
                        {"(sqrt (make-rectangular +inf.0 1.))", "+inf.0+0.0i"}, {"(sqrt (make-rectangular +inf.0 -1.))", "+inf.0-0.0i"},
                        {"(sqrt (make-rectangular 1. +inf.0))", "+inf.0+inf.0i"}, {"(sqrt (make-rectangular +inf.0 +inf.0))", "+inf.0+inf.0i"},
                        {"(sqrt (make-rectangular -inf.0 -inf.0))", "+inf.0-inf.0i"}, {"(sqrt (make-rectangular +nan.0 +inf.0))", "+inf.0+inf.0i"},
                        {"(sqrt (make-rectangular -inf.0 +nan.0))", "+nan.0+inf.0i"}, {"(sqrt (make-rectangular +inf.0 +nan.0))", "+inf.0+nan.0i"},
                        {"(sqrt (make-rectangular 1. +nan.0))", "+nan.0+nan.0i"}, {"(sqrt (make-rectangular +nan.0 1.))", "+nan.0+nan.0i"},
                        {"(sqrt (make-rectangular 0. 0.))", "0.0+0.0i"}, {"(sqrt (make-rectangular -0. -0.))", "0.0-0.0i"},
                        // ... and R7RS's cut: C99 gives 0.0-inf.0i below it
                        {"(sqrt (make-rectangular -inf.0 -0.))", "0.0+inf.0i"}, {"(sqrt (make-rectangular -inf.0 0.))", "0.0+inf.0i"}, {"(sqrt -inf.0)", "0.0+inf.0i"},
                        // cexp
                        {"(exp (make-rectangular +inf.0 0.))", "+inf.0+0.0i"}, {"(exp (make-rectangular +inf.0 -0.))", "+inf.0-0.0i"},
                        {"(exp (make-rectangular +nan.0 0.))", "+nan.0+0.0i"}, {"(exp (make-rectangular -inf.0 1.))", "0.0+0.0i"},
                        {"(exp (make-rectangular -inf.0 2.))", "-0.0+0.0i"}, {"(exp (make-rectangular +inf.0 2.))", "-inf.0+inf.0i"},
                        {"(exp (make-rectangular -inf.0 +inf.0))", "0.0+0.0i"}, {"(exp (make-rectangular +inf.0 +inf.0))", "+inf.0+nan.0i"},
                        {"(exp (make-rectangular -inf.0 +nan.0))", "0.0+0.0i"}, {"(exp (make-rectangular +inf.0 +nan.0))", "+inf.0+nan.0i"},
                        {"(exp (make-rectangular 1. +inf.0))", "+nan.0+nan.0i"}, {"(exp (make-rectangular +nan.0 1.))", "+nan.0+nan.0i"},
                        {"(exp (make-rectangular -0. -0.))", "1.0-0.0i"},
                        // clog (unchanged)
                        {"(log (make-rectangular -0. 0.))", "-inf.0+3.141592653589793i"}, {"(log (make-rectangular 0. -0.))", "-inf.0-0.0i"},
                        {"(log (make-rectangular 1. +inf.0))", "+inf.0+1.5707963267948966i"}, {"(log (make-rectangular -inf.0 1.))", "+inf.0+3.141592653589793i"},
                        {"(log (make-rectangular +inf.0 -1.))", "+inf.0-0.0i"}, {"(log (make-rectangular -inf.0 +inf.0))", "+inf.0+2.356194490192345i"},
                        {"(log (make-rectangular +inf.0 +inf.0))", "+inf.0+0.7853981633974483i"}, {"(log (make-rectangular -inf.0 +nan.0))", "+inf.0+nan.0i"},
                        {"(log (make-rectangular +nan.0 +inf.0))", "+inf.0+nan.0i"}, {"(log (make-rectangular +nan.0 1.))", "+nan.0+nan.0i"},
                        {"(log (make-rectangular 1. +nan.0))", "+nan.0+nan.0i"},
                        // casin(z) = -i casinh(iz)
                        {"(asin (make-rectangular 1. +inf.0))", "0.0+inf.0i"}, {"(asin (make-rectangular -1. -inf.0))", "-0.0-inf.0i"},
                        {"(asin (make-rectangular +inf.0 1.))", "1.5707963267948966+inf.0i"}, {"(asin (make-rectangular -inf.0 1.))", "-1.5707963267948966+inf.0i"},
                        {"(asin (make-rectangular +inf.0 +inf.0))", "0.7853981633974483+inf.0i"}, {"(asin (make-rectangular +nan.0 +inf.0))", "+nan.0+inf.0i"},
                        {"(asin (make-rectangular 0. +nan.0))", "0.0+nan.0i"}, {"(asin (make-rectangular 1. +nan.0))", "+nan.0+nan.0i"},
                        {"(asin (make-rectangular +nan.0 1.))", "+nan.0+nan.0i"}, {"(asin (make-rectangular +nan.0 0.))", "+nan.0+nan.0i"},
                        {"(asin (make-rectangular -0. -0.))", "-0.0-0.0i"}, {"(asin +nan.0)", "+nan.0"}, {"(acos +nan.0)", "+nan.0"}, // were +nan.0+nan.0i
                        // ... and R7RS's cut, the real number's value: C99 gives π/2+inf.0i above it
                        {"(asin (make-rectangular +inf.0 0.))", "1.5707963267948966-inf.0i"}, {"(asin +inf.0)", "1.5707963267948966-inf.0i"},
                        {"(asin (make-rectangular -inf.0 -0.))", "-1.5707963267948966+inf.0i"}, {"(asin -inf.0)", "-1.5707963267948966+inf.0i"},
                        // cacos
                        {"(acos (make-rectangular 0. 0.))", "1.5707963267948966-0.0i"}, {"(acos (make-rectangular -0. -0.))", "1.5707963267948966+0.0i"},
                        {"(acos (make-rectangular 0. +nan.0))", "1.5707963267948966+nan.0i"}, {"(acos (make-rectangular 1. +inf.0))", "1.5707963267948966-inf.0i"},
                        {"(acos (make-rectangular 1. -inf.0))", "1.5707963267948966+inf.0i"}, {"(acos (make-rectangular -inf.0 1.))", "3.141592653589793-inf.0i"},
                        {"(acos (make-rectangular +inf.0 1.))", "0.0-inf.0i"}, {"(acos (make-rectangular -inf.0 +inf.0))", "2.356194490192345-inf.0i"},
                        {"(acos (make-rectangular +inf.0 +inf.0))", "0.7853981633974483-inf.0i"}, {"(acos (make-rectangular +inf.0 +nan.0))", "+nan.0+inf.0i"},
                        {"(acos (make-rectangular +nan.0 +inf.0))", "+nan.0-inf.0i"}, {"(acos (make-rectangular 1. +nan.0))", "+nan.0+nan.0i"},
                        {"(acos (make-rectangular +nan.0 1.))", "+nan.0+nan.0i"},
                        // ... and R7RS's cut, the real number's value: C99 gives 0.0-inf.0i above it
                        {"(acos (make-rectangular +inf.0 0.))", "0.0+inf.0i"}, {"(acos +inf.0)", "0.0+inf.0i"}, {"(acos -inf.0)", "3.141592653589793-inf.0i"},
                        // catan(z) = -i catanh(iz); at ±i, R7RS's formula's log 0 is C99's catanh(1)
                        {"(atan (make-rectangular 1. +inf.0))", "1.5707963267948966+0.0i"}, {"(atan (make-rectangular +inf.0 1.))", "1.5707963267948966+0.0i"},
                        {"(atan (make-rectangular -inf.0 1.))", "-1.5707963267948966+0.0i"}, {"(atan (make-rectangular +inf.0 -inf.0))", "1.5707963267948966-0.0i"},
                        {"(atan (make-rectangular +nan.0 +inf.0))", "+nan.0+0.0i"}, {"(atan (make-rectangular +nan.0 0.))", "+nan.0+0.0i"},
                        {"(atan (make-rectangular 0. +nan.0))", "+nan.0+nan.0i"}, {"(atan (make-rectangular 1. +nan.0))", "+nan.0+nan.0i"},
                        {"(atan (make-rectangular 0. 1.))", "0.0+inf.0i"}, {"(atan (make-rectangular -0. -1.))", "-0.0-inf.0i"}, {"(atan +i)", "0.0+inf.0i"},
                        {"(atan (make-rectangular -0. -0.))", "-0.0-0.0i"},
        };
        assertWrites(cases);
    }

    /**
     * M13-02 (#280's follow-up). Kahan's formulas ("Branch Cuts for Complex Elementary Functions",
     * 1987) for sqrt, asin, acos and atan: sqrt's other part is y/2t, not a difference r - x that
     * cancels; asin's and acos's are arctangents and arcsinhs of products of the roots of 1 - z
     * and 1 + z, not π/2 - asin z and the log of a sum that cancel; atan's real part is a log1p, not
     * a difference of logs. The expected values are the correctly rounded ones (mpmath at 400
     * bits); before, a few ulps to most of the digits off.
     */
    @Test
    public void kahansFormulasDoNotCancel() {
        String[][] cases = {
                        // were 1.0+0.0i, 0.0+1.0i, 0.7853981633974483+5.000000413701855e-11i
                        {"(sqrt 1.+1e-10i)", "1.0+5.0e-11i"}, {"(sqrt -1.+1e-10i)", "5.0e-11+1.0i"}, {"(atan 1.+1e-10i)", "0.7853981633974483+5.0e-11i"},
                        // were 9.999999999e-11+1.000000082690371e-10i and 1.5707963266948965-1.000000082690371e-10i
                        {"(asin 1e-10+1e-10i)", "1.0e-10+1.0e-10i"}, {"(acos 1e-10+1e-10i)", "1.5707963266948965-1.0e-10i"},
                        // was 0.5073563032171444+1.4693517443681852i (π/2 - asin z)
                        {"(acos 2.-1.i)", "0.5073563032171445+1.4693517443681852i"}, {"(atan +2.i)", "1.5707963267948966+0.5493061443340549i"}, // was ...548i
                        {"(atan .5+.5i)", "0.5535743588970452+0.40235947810852507i"}, // was ...251i
                        // a complex number on the real axis, off the cuts, is the real number's value with the zero's sign:
                        // were 0.5235987755982989-1.1102230246251565e-16i and 1.0471975511965976-1.1102230246251565e-16i
                        {"(asin (make-rectangular .5 0.))", "0.5235987755982989+0.0i"}, {"(acos (make-rectangular .5 -0.))", "1.0471975511965979+0.0i"},
                        {"(atan (make-rectangular 3. -0.))", "1.2490457723982544-0.0i"},
        };
        assertWrites(cases);
    }

    /** Runs each case's expression, a thunk each, and compares what it writes (or its error's message). */
    private static void assertWrites(String[][] cases) {
        StringBuilder program = new StringBuilder("(for-each (lambda (thunk) (write (guard (e ((error-object? e) (error-object-message e))) (thunk))) (newline)) (list");
        for (String[] c : cases) {
            program.append(" (lambda () ").append(c[0]).append(')');
        }
        program.append("))");
        String[] got = output(program.toString()).split("\n");
        List<String> wrong = new ArrayList<>();
        for (int i = 0; i < cases.length; i++) {
            String g = i < got.length ? got[i] : "(missing)";
            if (!g.equals(cases[i][1])) {
                wrong.add(cases[i][0] + " is " + g + ", not " + cases[i][1]);
            }
        }
        assertTrue(wrong.size() + ": " + wrong, wrong.isEmpty());
    }

    /** The program's output: unlike {@link #write}, the program writes it. */
    private static String output(String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("scheme").out(out).build()) {
            context.eval("scheme", program);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /**
     * A polar literal {@code r@θ} reads as the number {@code (make-polar r θ)} returns, bit for bit:
     * both are {@code r cos θ} and {@code r sin θ} with fdlibm's cos and sin (design.md D1), so a
     * literal is the same on every platform and in both launchers.
     */
    @Test
    public void polarLiteralsAreMakePolars() {
        java.util.Random random = new java.util.Random(20261117);
        StringBuilder literals = new StringBuilder();
        StringBuilder calls = new StringBuilder();
        List<Double> expected = new ArrayList<>();
        String[][] exact = {{"1", "1"}, {"2", "1/3"}, {"-3/4", "7"}, {"1", "-2"}, {"10", "100"}, {"3/2", "5/2"}, {"#e1.5", "2.5"}};
        for (String[] e : exact) {
            literals.append(' ').append(e[0]).append('@').append(e[1]);
            calls.append(" (make-polar ").append(e[0]).append(' ').append(e[1]).append(')');
        }
        for (int i = 0; i < 200; i++) {
            double r = Math.copySign(Math.exp(random.nextDouble() * 20 - 10), random.nextDouble() - 0.5);
            double theta = i % 2 == 0 ? random.nextDouble() * 20 - 10 : Math.copySign(Math.exp(random.nextDouble() * 50 - 20), random.nextDouble() - 0.5);
            String rs = Double.toString(r).replace('E', 'e');
            String ts = Double.toString(theta).replace('E', 'e');
            literals.append(' ').append(rs).append('@').append(ts);
            calls.append(" (make-polar ").append(rs).append(' ').append(ts).append(')');
            expected.add(r * StrictMath.cos(theta));
            expected.add(r * StrictMath.sin(theta));
        }
        String out = write("(let ((read '(" + literals + ")) (made (list" + calls + "))) " +
                        "(list (map eqv? read made) (map (lambda (z) (list (real-part z) (imag-part z))) (list-tail read " + exact.length + "))))");
        String[] parts = out.replaceAll("[()]", " ").trim().split("\\s+");
        List<String> wrong = new ArrayList<>();
        for (int i = 0; i < exact.length + expected.size() / 2; i++) {
            if (!parts[i].equals("#t")) {
                wrong.add("literal " + i + " isn't eqv? to its make-polar");
            }
        }
        for (int i = 0; i < expected.size(); i++) {
            String got = parts[exact.length + expected.size() / 2 + i];
            if (Double.doubleToRawLongBits(Double.parseDouble(got)) != Double.doubleToRawLongBits(expected.get(i))) {
                wrong.add("part " + i + " is " + got + ", not " + expected.get(i));
            }
        }
        assertTrue(wrong.size() + ": " + wrong, wrong.isEmpty());
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

    /**
     * M13-06: the division of big integers (the builtins' {@code ArithmeticDivision}: Knuth's
     * algorithm D on 64-bit digits, between the JDK's thresholds) against
     * {@link BigInteger#divideAndRemainder}, by {@code truncate/} and {@code floor/} and their
     * quotients and remainders, on random operands of both signs at and around each threshold:
     * a divisor and a quotient of 8 32-bit digits (below either the JDK divides), the JDK's
     * Burnikel-Ziegler thresholds (a divisor of 80 digits and a quotient of 40), and a divisor of
     * 256 digits (past it and those, the JDK divides again); at the 64-bit digits' edges; and with operands that make
     * the algorithm's rare steps happen: a top digit equal to the divisor's (the quotient digit
     * estimated as B - 1), and the quotient digit one too large after its test (D6's add-back).
     */
    @Test
    public void bigDivisionMatchesTheJdk() {
        Random random = new Random(1306);
        List<BigInteger[]> cases = new ArrayList<>();
        // Divisors (in bits) on both sides of 8, 80 and 256 32-bit digits, and dividends of 8 and 40
        // digits more, where ArithmeticDivision's choice changes.
        int[] divisorBits = {63, 64, 65, 127, 128, 129, 224, 225, 256, 257, 32 * 79, 32 * 79 + 1, 32 * 80, 32 * 80 + 1, 32 * 255, 32 * 255 + 1, 32 * 256, 32 * 256 + 1};
        for (int b : divisorBits) {
            int bInts = (b + 31) / 32;
            int[] dividendBits = {b - 1, b, b + 1, b + 64, b + random.nextInt(3000), 32 * (bInts + 7), 32 * (bInts + 7) + 1, 32 * (bInts + 8), 32 * (bInts + 8) + 1, 32 * (bInts + 39),
                            32 * (bInts + 39) + 1, 32 * (bInts + 40), 32 * (bInts + 40) + 1};
            for (int a : dividendBits) {
                for (int kind = 0; kind < (b < 8000 ? 4 : 1); kind++) {
                    cases.add(new BigInteger[]{operand(random, a, kind), operand(random, b, (kind + 1) % 4)});
                }
            }
        }
        for (int i = 0; i < 300; i++) {
            int b = 2 + random.nextInt(3200);
            cases.add(new BigInteger[]{operand(random, b + random.nextInt(3200) - 64, random.nextInt(4)), operand(random, b, random.nextInt(4))});
        }
        // Near multiples of the divisor: a remainder of 0 or close to it, or to the divisor.
        for (int i = 0; i < 100; i++) {
            BigInteger b = operand(random, 65 + random.nextInt(3000), random.nextInt(4));
            BigInteger q = operand(random, 1 + random.nextInt(1500), random.nextInt(4));
            cases.add(new BigInteger[]{b.multiply(q).add(BigInteger.valueOf(random.nextInt(5) - 2)), b});
            cases.add(new BigInteger[]{b.multiply(q).add(b.abs()).subtract(BigInteger.ONE), b});
        }
        // 2^(t+1) / (2^t + 1): the top digits make the estimate 2 and the divisor's last digit, below
        // the two the estimate's test sees, makes it 1 (an add-back, for a divisor of three digits or
        // more). b 2^64 - 1 over b: the remainder's top digit equals the divisor's (B - 1, then an
        // add-back too).
        for (int t = 120; t < 400; t += 7) {
            BigInteger b = BigInteger.ONE.shiftLeft(t).add(BigInteger.ONE);
            cases.add(new BigInteger[]{BigInteger.ONE.shiftLeft(t + 1), b});
            cases.add(new BigInteger[]{BigInteger.ONE.shiftLeft(t + 1).multiply(operand(random, 200, 0)).negate(), b});
            BigInteger c = operand(random, t, 0).abs();
            cases.add(new BigInteger[]{c.shiftLeft(64).subtract(BigInteger.ONE), c});
            cases.add(new BigInteger[]{c.shiftLeft(64).subtract(BigInteger.ONE).negate(), c});
        }
        StringBuilder program = new StringBuilder("(define cases '(");
        StringBuilder expected = new StringBuilder("(");
        for (BigInteger[] c : cases) {
            BigInteger a = c[0];
            BigInteger b = c[1];
            program.append('(').append(a).append(' ').append(b).append(')');
            BigInteger[] truncated = a.divideAndRemainder(b);
            BigInteger[] floored = truncated.clone();
            if (floored[1].signum() != 0 && floored[1].signum() != b.signum()) {
                floored[0] = floored[0].subtract(BigInteger.ONE);
                floored[1] = floored[1].add(b);
            }
            expected.append(expected.length() == 1 ? "" : " ").append('(').append(String.join(" ", truncated[0].toString(), truncated[1].toString(), floored[0].toString(), floored[1].toString(),
                            truncated[0].toString(), truncated[1].toString(), floored[1].toString())).append(')');
        }
        program.append("))\n(map (lambda (c) (let ((a (car c)) (b (cadr c)))" + " (append (call-with-values (lambda () (truncate/ a b)) list) (call-with-values (lambda () (floor/ a b)) list)" +
                        " (list (quotient a b) (remainder a b) (modulo a b))))) cases)");
        expected.append(')');
        assertEquals(expected.toString(), write("(let () " + program + ")"));
    }

    /** A random integer of {@code bits} bits (at least 1) and either sign, by kind: random, all ones, a power of two, or 2^bits less a small random number. */
    private static BigInteger operand(Random random, int bits, int kind) {
        int n = Math.max(1, bits);
        BigInteger x = switch (kind) {
            case 1 -> BigInteger.ONE.shiftLeft(n).subtract(BigInteger.ONE);
            case 2 -> BigInteger.ONE.shiftLeft(n - 1);
            case 3 -> BigInteger.ONE.shiftLeft(n).subtract(new BigInteger(Math.max(1, n / 3), random)).max(BigInteger.ONE);
            default -> new BigInteger(n, random).setBit(n - 1);
        };
        return random.nextBoolean() ? x : x.negate();
    }
}
