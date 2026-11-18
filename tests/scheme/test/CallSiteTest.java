package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.graalvm.polyglot.Context;
import org.junit.Assume;
import org.junit.Test;

import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.Instruction;

import scheme.compiler.ProgramCompiler;
import scheme.runtime.GlobalCell;
import scheme.runtime.SchemeContext;
import scheme.runtime.SchemeLanguage;
import scheme.runtime.value.SClosure;

/**
 * M7-02: smaller call sites (design.md "Calls and tail calls", "Continuations").
 *
 * <ul>
 * <li>Every non-tail call site checks for a capture in a few instructions: the call, then a
 * short-circuit that suspends the frame only for a capture. The instructions count toward Truffle's
 * size estimate of a procedure, which decides whether Truffle splits it ({@code map}'s and
 * {@code for-each}'s were just over the limit, plan.md M5-10).</li>
 * <li>A named {@code let} (or {@code do}) entered in tail position, whose procedure is only called
 * in tail position in its own body, is a loop of the root that enters it: no closure, no call.</li>
 * <li>The trampoline reports the tail calls it performs as loop iterations, so a procedure whose call
 * site performs many of them is compiled like one with a loop.</li>
 * </ul>
 *
 * The semantics tests run under {@code //tests:unit_torture} too, with a capture and resume at
 * every non-tail call.
 */
public class CallSiteTest {

    private static final String SCHEME = "scheme";

    /** Truffle's default {@code engine.SplittingMaxCalleeSize}: larger procedures are never split. */
    private static final int SPLITTING_MAX_CALLEE_SIZE = 100;

    private static String run(String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            context.eval(SCHEME, program);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** As an isolated program, then as text in the interaction environment. */
    private static void check(String expected, String body) {
        assertEquals(body, expected, run("(import (scheme base) (scheme write))\n" + body));
        assertEquals(body, expected, run(body));
    }

    /**
     * The root of the procedure the interaction environment binds {@code name} to, after
     * {@code program}. No instrument is attached, so the bytecode has no instrumentation
     * instructions, as when Truffle decides whether to split.
     */
    private static BytecodeRootNode root(Context context, String program, String name) {
        context.initialize(SCHEME);
        if (!program.isEmpty()) {
            context.eval(SCHEME, program);
        }
        context.enter();
        try {
            SchemeContext scheme = SchemeContext.get(null);
            GlobalCell cell = new ProgramCompiler().interactionCell(scheme, scheme.symbols.intern(name));
            return (BytecodeRootNode) ((SClosure) cell.valueOrNull()).callTarget.getRootNode();
        } finally {
            context.leave();
        }
    }

    private static List<Instruction> instructions(BytecodeRootNode root) {
        List<Instruction> list = new ArrayList<>();
        for (Instruction instruction : root.getBytecodeNode().getInstructions()) {
            list.add(instruction);
        }
        return list;
    }

    private static List<String> names(BytecodeRootNode root) {
        List<String> names = new ArrayList<>();
        for (Instruction instruction : instructions(root)) {
            names.add(instruction.getName());
        }
        return names;
    }

    /**
     * The Bytecode DSL's size estimate of a root, which Truffle compares with
     * {@code SplittingMaxCalleeSize}: its bytecode's length in bytes over 6, the median instruction
     * length.
     */
    private static int sizeEstimate(BytecodeRootNode root) {
        int bytes = 0;
        for (Instruction instruction : instructions(root)) {
            bytes += instruction.getLength();
        }
        return bytes / 6;
    }

    /** The root and the roots of the lambdas in it, recursively. */
    private static List<BytecodeRootNode> withNested(BytecodeRootNode root) {
        List<BytecodeRootNode> roots = new ArrayList<>();
        roots.add(root);
        for (int i = 0; i < roots.size(); i++) {
            for (Instruction instruction : instructions(roots.get(i))) {
                for (Instruction.Argument argument : instruction.getArguments()) {
                    if (argument.getKind() == Instruction.Argument.Kind.CONSTANT && argument.asConstant() instanceof BytecodeRootNode nested) {
                        roots.add(nested);
                    }
                }
            }
        }
        return roots;
    }

    private static boolean has(List<String> instructions, String operation) {
        for (String name : instructions) {
            if (name.toLowerCase(java.util.Locale.ROOT).contains(operation.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------
    // The call site

    @Test
    public void aNonTailCallChecksForACaptureInAFewInstructions() {
        try (Context context = Context.create(SCHEME)) {
            List<String> code = names(root(context, "(define (f g) (g) 'done)", "f"));
            int call = code.indexOf("c.Call");
            int done = code.subList(call, code.size()).indexOf("load.constant") + call;
            assertTrue(code.toString(), call >= 0 && done > call);
            // What follows the call up to the next expression: the capture check, the suspension and
            // the resumption, and the pop of the unused value. It was 11 instructions (store and
            // load a temporary, IsCapture, a branch, SuspendFrame, Resumed, a branch, a load and a
            // clear of the temporary, the pop).
            List<String> site = code.subList(call + 1, done);
            assertTrue(site.toString(), site.size() <= 6);
            assertFalse(site.toString(), has(site, "local"));
        }
    }

    @Test
    public void mapAndForEachFitTheSplittingLimit() {
        try (Context context = Context.create(SCHEME)) {
            for (String procedure : new String[]{"map", "for-each"}) {
                for (BytecodeRootNode root : withNested(root(context, "", procedure))) {
                    assertTrue(root + " in " + procedure + ": " + sizeEstimate(root), sizeEstimate(root) <= SPLITTING_MAX_CALLEE_SIZE);
                }
            }
        }
    }

    /**
     * What the engine logs while it runs {@code program}, with the given engine options (and the
     * compiler, synchronously, once a root has been called 100 times). Skips the test when the
     * runtime has no compiler: then the options don't exist.
     */
    private static String engineLog(String program, String... options) throws IOException {
        Path log = Files.createTempFile("call-site", ".log");
        Context.Builder builder = Context.newBuilder(SCHEME).allowExperimentalOptions(true).option("log.file", log.toString()).out(new ByteArrayOutputStream());
        builder.option("engine.Compilation", "true").option("engine.CompileImmediately", "false").option("engine.BackgroundCompilation", "false");
        builder.option("engine.MultiTier", "false").option("engine.SingleTierCompilationThreshold", "100");
        for (int i = 0; i < options.length; i += 2) {
            builder.option(options[i], options[i + 1]);
        }
        Context context;
        try {
            context = builder.build();
        } catch (IllegalArgumentException e) {
            Assume.assumeNoException("no optimizing Truffle runtime", e);
            throw e;
        }
        try (Context c = context) {
            c.eval(SCHEME, program);
        }
        try {
            return Files.readString(log);
        } finally {
            Files.delete(log);
        }
    }

    @Test
    public void mapAndForEachAreSplitPerCallSite() throws IOException {
        // Under torture each loop iteration after a call goes on in a copy of the loop's frame,
        // resumed by the program's prompt, not called from the call site that called map.
        Assume.assumeFalse("torture mode", SchemeLanguage.constant(SchemeLanguage.TORTURE) != SchemeLanguage.Torture.OFF);
        // Five callers each, each with a procedure of its own: more than a call site's cache holds,
        // so the site in map's (for-each's) loop goes megamorphic, and Truffle splits the procedure
        // for each caller, whose copy's site sees one procedure.
        StringBuilder program = new StringBuilder("(define s 0) (define l '(1 2 3 4 5 6 7 8 9 10))");
        StringBuilder calls = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            program.append("(define (m").append(i).append(" l) (map (lambda (y) (+ y ").append(i).append(")) l))");
            program.append("(define (m2").append(i).append(" l) (map (lambda (y z) (+ y z ").append(i).append(")) l l))");
            program.append("(define (f").append(i).append(" l) (for-each (lambda (y) (set! s (+ s y ").append(i).append("))) l))");
            program.append("(define (f2").append(i).append(" l) (for-each (lambda (y z) (set! s (+ s y z ").append(i).append("))) l l))");
            calls.append(" (m").append(i).append(" l) (m2").append(i).append(" l) (f").append(i).append(" l) (f2").append(i).append(" l)");
        }
        program.append("(let loop ((i 0)) (if (< i 3000) (begin").append(calls).append(" (loop (+ i 1)))))");
        String log = engineLog(program.toString(), "engine.TraceSplitting", "true");
        for (String procedure : new String[]{"map", "for-each"}) {
            long splits = Pattern.compile("split\\s+\\S+\\s+" + Pattern.quote(procedure) + "\\s").matcher(log).results().count();
            // Ten callers each (one and two lists): a copy for each.
            assertTrue(procedure + " split " + splits + " times:\n" + log, splits >= 10);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Named let as a loop of the enclosing root

    @Test
    public void aNamedLetInTailPositionIsALoopOfItsRoot() {
        try (Context context = Context.create(SCHEME)) {
            List<String> code = names(root(context, "(define (f n) (let loop ((i 0) (acc '())) (if (= i n) acc (loop (+ i 1) (cons i acc)))))", "f"));
            assertFalse(code.toString(), has(code, "MakeClosure") || has(code, "MakeTailCall"));
            code = names(root(context, "(define (g v) (do ((i 0 (+ i 1))) ((= i (vector-length v)) v) (vector-set! v i i)))", "g"));
            assertFalse(code.toString(), has(code, "MakeClosure") || has(code, "MakeTailCall"));
            // Nested loops, the inner one going back to the outer one.
            code = names(root(context, "(define (h n) (let outer ((i 0) (acc 0)) (if (= i n) acc" +
                            " (let inner ((j 0) (acc acc)) (if (= j i) (outer (+ i 1) acc) (inner (+ j 1) (+ acc 1)))))))", "h"));
            assertFalse(code.toString(), has(code, "MakeClosure") || has(code, "MakeTailCall"));
        }
        assertEquals("(2 1 0)", run("(define (f n) (let loop ((i 0) (acc '())) (if (= i n) acc (loop (+ i 1) (cons i acc))))) (display (f 3))"));
        assertEquals("45", run("(define (h n) (let outer ((i 0) (acc 0)) (if (= i n) acc" +
                        " (let inner ((j 0) (acc acc)) (if (= j i) (outer (+ i 1) acc) (inner (+ j 1) (+ acc 1))))))) (display (h 10))"));
    }

    @Test
    public void otherNamedLetsStayProcedures() {
        try (Context context = Context.create(SCHEME)) {
            // A non-tail call of the loop, the loop passed as a value, called from a nested lambda,
            // or with another number of arguments; and a named let in a non-tail position.
            for (String body : new String[]{
                            "(let loop ((i n)) (if (= i 0) 0 (+ 1 (loop (- i 1)))))",
                            "(let loop ((i n)) (if (= i 0) loop (loop (- i 1))))",
                            "(let loop ((i n)) (if (= i 0) 0 (let ((g (lambda () (loop (- i 1))))) (g))))",
                            "(let loop ((i n)) (if (= i 0) 0 (loop (- i 1) 1)))",
                            "(+ 1 (let loop ((i n)) (if (= i 0) 0 (loop (- i 1)))))"}) {
                List<String> code = names(root(context, "(define (f n) " + body + ")", "f"));
                assertTrue(body + ": " + code, has(code, "MakeClosure"));
            }
            // A loop that calls a procedure it was given stays a procedure: its call site is the one
            // that goes megamorphic, and Truffle can split the loop for each caller.
            for (String body : new String[]{
                            "(let loop ((l l)) (if (pair? l) (begin (g (car l)) (loop (cdr l)))))",
                            "(let loop ((l l)) (if (pair? l) (begin (apply g (car l) '()) (loop (cdr l)))))"}) {
                List<String> code = names(root(context, "(define (f g l) " + body + ")", "f"));
                assertTrue(body + ": " + code, has(code, "MakeClosure"));
            }
        }
        check("(3 2 1)", "(define (f g l) (let loop ((l l) (acc '())) (if (pair? l) (loop (cdr l) (cons (g (car l)) acc)) acc))) (display (f (lambda (x) (+ x 1)) '(0 1 2)))");
        check("3", "(define (f n) (let loop ((i n)) (if (= i 0) 0 (+ 1 (loop (- i 1)))))) (display (f 3))");
        check("#t", "(define (f n) (let loop ((i n)) (if (= i 0) loop (loop (- i 1))))) (display (procedure? (f 3)))");
        check("0", "(define (f n) (let loop ((i n)) (if (= i 0) 0 (let ((g (lambda () (loop (- i 1))))) (g))))) (display (f 3))");
        check("caught", "(define (f n) (let loop ((i n)) (if (= i 0) 0 (loop (- i 1) 1)))) (display (guard (e (#t 'caught)) (f 3)))");
        check("1", "(define (f n) (+ 1 (let loop ((i n)) (if (= i 0) 0 (loop (- i 1)))))) (display (f 3))");
    }

    @Test
    public void contifiedLoopSemantics() {
        // A million iterations, in constant space.
        check("1000000", "(define (f n) (let loop ((i 0)) (if (= i n) i (loop (+ i 1))))) (display (f 1000000))");
        // Closures made in the loop capture their own iteration's variables, assigned ones included:
        // each iteration binds them afresh, as a call would.
        check("(2 1 0)", "(define (f) (let loop ((i 0) (fs '())) (if (= i 3) (map (lambda (g) (g)) fs) (loop (+ i 1) (cons (lambda () i) fs))))) (display (f))");
        check("(12 11 10)", "(define (f) (let loop ((i 0) (fs '())) (if (= i 3) (map (lambda (g) (g)) fs)" +
                        " (begin (set! i (+ i 10)) (loop (- i 9) (cons (lambda () i) fs)))))) (display (f))");
        // The loop's variables shadow the enclosing procedure's, and its inits see those.
        check("(3 . 1)", "(define (f i) (let loop ((i (+ i 1)) (n i)) (if (> i 2) (cons i n) (loop (+ i 1) n)))) (display (f 1))");
        // The body's other tail calls are still tail calls, and a tail call of the enclosing
        // procedure itself still loops.
        check("done", "(define (g n) (if (= n 0) 'done (f n))) (define (f n) (let loop ((i 3)) (if (= i 0) (g (- n 1)) (loop (- i 1))))) (display (f 100000))");
        check("done", "(define (f n) (let loop ((i 3)) (cond ((= n 0) 'done) ((= i 0) (f (- n 1))) (else (loop (- i 1)))))) (display (f 100000))");
        // An error in the loop, and an arity error of the entry.
        check("caught", "(define (f n) (let loop ((i n)) (if (= i 0) (car '()) (loop (- i 1))))) (display (guard (e (#t 'caught)) (f 3)))");
        check("6", "(define (f l) (do ((l l (cdr l)) (s 0 (+ s (car l)))) ((null? l) s))) (display (f '(1 2 3)))");
    }

    @Test
    public void reenteringAContifiedLoop() {
        // A continuation captured in an iteration, re-entered: the loop goes on from that
        // iteration's variables, every time.
        check("((0 1 2) (0 1 2) (0 1 2))", """
                        (define (test)
                          (define k #f)
                          (define results '())
                          (define (f)
                            (let loop ((i 0) (acc '()))
                              (if (= i 3)
                                  (reverse acc)
                                  (begin
                                    (if (= i 1) (call/cc (lambda (c) (set! k c))))
                                    (loop (+ i 1) (cons i acc))))))
                          (set! results (cons (f) results))
                          (if (< (length results) 3) (k #f))
                          results)
                        (display (test))""");
        // A generator: each re-entry continues from where the loop left off.
        check("(0 1 2 done)", """
                        (define (make-gen n)
                          (define return #f)
                          (define resume #f)
                          (define (next)
                            (call/cc (lambda (r)
                              (set! return r)
                              (if resume
                                  (resume #f)
                                  (let loop ((i 0))
                                    (if (= i n)
                                        (return 'done)
                                        (begin (call/cc (lambda (c) (set! resume c) (return i)))
                                               (loop (+ i 1)))))))))
                          next)
                        (define g (make-gen 3))
                        (display (let* ((a (g)) (b (g)) (c (g)) (d (g))) (list a b c d)))""");
    }

    // ------------------------------------------------------------------------------------------
    // Loop counts

    @Test
    public void tailCallsAtACallSiteCountAsLoopIterations() throws IOException {
        // run is called three times, but each call's site performs a hundred thousand tail calls:
        // reported as loop iterations, they get run compiled (100 calls and iterations, here).
        String log = engineLog("(define (ev? n) (if (= n 0) #t (od? (- n 1)))) (define (od? n) (if (= n 0) #f (ev? (- n 1))))" +
                        "(define (run n) (if (ev? n) 'even 'odd)) (run 100000) (run 100001) (run 100000)",
                        "engine.TraceCompilation", "true");
        assertTrue(log, Pattern.compile("opt done\\s+engine=\\d+\\s+id=\\d+\\s+run\\s").matcher(log).find());
    }
}
