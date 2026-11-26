package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.junit.Assume;
import org.junit.Test;

import scheme.runtime.SchemeLanguage;

/**
 * M13-10: the interpreter calls a procedure through Truffle 25.6's calls that name the callee's
 * root class ({@code DirectCallNode.call(Class, int, Object[])}, {@code IndirectCallNode.call(RootNode,
 * int, Object[])}): an interpreted call then runs the callee's root in the caller's host frame
 * ({@code OptimizedCallTarget.callRoot}), where the untyped call went through the call target's
 * boundary stub ({@code callBoundary}) and its profiled entry (design.md "Calls and tail calls").
 *
 * <p>A host function the program calls records the Java stack: the innermost Scheme frame must have
 * been entered by {@code callRoot}, from the site the case names. The context doesn't compile, so
 * every frame is interpreted, whatever the test mode; the tortures resume every frame from the
 * prompt's driver, so they check only the entry.
 */
public class TypedCallTest {

    private static final String SCHEME = "scheme";
    private static final String CALL_TARGET = "com.oracle.truffle.runtime.OptimizedCallTarget";
    /** The Bytecode DSL's root class, and its continuation roots' ({@code $ContinuationRootNodeImpl}). */
    private static final String BYTECODE_ROOT = "scheme.runtime.SchemeRootNodeGen";
    /** The call target's methods that enter a root, typed or not. */
    private static final Set<String> ENTRIES = Set.of("callRoot", "callDirect", "callIndirect", "callInlined", "callBoundary", "call");

    /** The Java stacks the host function {@code probe} saw, one per call. */
    private static List<StackTraceElement[]> run(String program) {
        List<StackTraceElement[]> stacks = new ArrayList<>();
        Context.Builder builder = Context.newBuilder(SCHEME).allowExperimentalOptions(true).allowHostAccess(HostAccess.ALL).out(new ByteArrayOutputStream());
        builder.option("engine.Compilation", "false");
        try (Context context = builder.build()) {
            context.getBindings(SCHEME).putMember("probe", (ProxyExecutable) args -> {
                stacks.add(Thread.currentThread().getStackTrace());
                return 0;
            });
            context.eval(SCHEME, program);
        }
        return stacks;
    }

    private static boolean tortured() {
        return SchemeLanguage.constant(SchemeLanguage.TORTURE) != SchemeLanguage.Torture.OFF;
    }

    /**
     * Checks that the innermost Scheme frame of {@code stack} was entered through a typed call, from
     * a method of ours named {@code site} ({@code Class$Inner.method}; null for any).
     */
    private static void assertTypedEntry(String what, StackTraceElement[] stack, String site) {
        // The innermost Scheme frame's root (a procedure's, or a resumed frame's continuation root),
        // then the call target's method that entered it.
        int root = -1;
        for (int i = 0; i < stack.length && root < 0; i++) {
            if (stack[i].getClassName().startsWith(BYTECODE_ROOT) && stack[i].getMethodName().equals("execute")) {
                root = i;
            }
        }
        assertTrue(what + ": no Scheme frame\n" + render(stack), root >= 0);
        int entry = -1;
        for (int i = root + 1; i < stack.length; i++) {
            if (stack[i].getClassName().equals(CALL_TARGET) && ENTRIES.contains(stack[i].getMethodName())) {
                entry = i;
                break;
            }
        }
        assertTrue(what + ": no call target entered a root\n" + render(stack), entry >= 0);
        assertEquals(what + ": the entry\n" + render(stack), "callRoot", stack[entry].getMethodName());
        if (site == null || tortured()) {
            return;
        }
        // The first frame of ours below the entry, past the call nodes and RootNode.call.
        for (int i = entry + 1; i < stack.length; i++) {
            String name = stack[i].getClassName();
            if (name.startsWith("scheme.")) {
                String method = name.substring(name.lastIndexOf('.') + 1) + "." + stack[i].getMethodName();
                if (method.equals(site)) {
                    return;
                }
                // A helper of ours that makes the typed call for the site.
                if (method.startsWith("RootCalls.")) {
                    continue;
                }
                throw new AssertionError(what + ": called from " + method + ", not " + site + "\n" + render(stack));
            }
        }
        throw new AssertionError(what + ": no site of ours below the entry\n" + render(stack));
    }

    private static String render(StackTraceElement[] stack) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(stack.length, 60); i++) {
            b.append("  at ").append(stack[i]).append('\n');
        }
        return b.toString();
    }

    @Test
    public void aCallSiteCallsAProcedureByItsRootClass() {
        List<StackTraceElement[]> stacks = run("(define (f) (probe) 1) (define (g) (+ (f) 1)) (g)");
        assertEquals(1, stacks.size());
        assertTypedEntry("a call site's cached procedure", stacks.get(0), "SchemeRootNode$Call.doDirect");
    }

    @Test
    public void aMegamorphicCallSiteCallsByTheRoot() {
        StringBuilder program = new StringBuilder("(define (call-it h) (+ (h) 1))\n");
        for (int i = 0; i < 6; i++) {
            program.append("(define (a").append(i).append(") (probe) ").append(i).append(")\n");
        }
        for (int i = 0; i < 6; i++) {
            program.append("(call-it a").append(i).append(")\n");
        }
        List<StackTraceElement[]> stacks = run(program.toString());
        assertEquals(6, stacks.size());
        assertTypedEntry("a cached procedure", stacks.get(0), "SchemeRootNode$Call.doDirect");
        assertTypedEntry("past the cache", stacks.get(5), "SchemeRootNode$Call.doIndirect");
    }

    @Test
    public void aTrampolineCallsATailCalledProcedureByItsRootClass() {
        List<StackTraceElement[]> stacks = run("(define (f) (probe) 1) (define (h) (f)) (define (g) (+ (h) 1)) (g)");
        assertEquals(1, stacks.size());
        assertTypedEntry("a tail call's cached procedure", stacks.get(0), "Trampoline$TailCalls.doDirect");
    }

    @Test
    public void aMegamorphicTrampolineCallsByTheRoot() {
        StringBuilder program = new StringBuilder("(define (h x) (x)) (define (g x) (+ (h x) 1))\n");
        for (int i = 0; i < 6; i++) {
            program.append("(define (a").append(i).append(") (probe) ").append(i).append(")\n");
        }
        for (int i = 0; i < 6; i++) {
            program.append("(g a").append(i).append(")\n");
        }
        List<StackTraceElement[]> stacks = run(program.toString());
        assertEquals(6, stacks.size());
        assertTypedEntry("a cached tail call", stacks.get(0), "Trampoline$TailCalls.doDirect");
        assertTypedEntry("a tail call past the cache", stacks.get(5), "Trampoline$TailCalls.doIndirect");
    }

    @Test
    public void thePromptCallsTheProgramByItsRootClass() {
        List<StackTraceElement[]> stacks = run("(probe)");
        assertEquals(1, stacks.size());
        assertTypedEntry("a top-level form", stacks.get(0), "PromptRootNode.execute");
    }

    @Test
    public void aResumedFrameIsCalledByItsRootClass() {
        // k re-enters f's frame after its call/cc: the prompt's driver resumes the frame's segment.
        List<StackTraceElement[]> stacks = run("""
                        (define k #f)
                        (define n 0)
                        (define (f) (+ (call/cc (lambda (c) (set! k c) 0)) (probe)))
                        (define (g) (+ (f) 1))
                        (g)
                        (if (< n 1) (begin (set! n (+ n 1)) (k 1)))
                        """);
        assertEquals(2, stacks.size());
        assertTypedEntry("a resumed frame", stacks.get(1), "PromptDriver$ResumeNode.doDirect");
    }

    @Test
    public void everyCallIsTypedUnderTorture() {
        Assume.assumeTrue("not a torture mode", tortured());
        List<StackTraceElement[]> stacks = run("(define (f) (probe) 1) (define (h) (f)) (define (g) (+ (h) (f) 1)) (g)");
        assertEquals(2, stacks.size());
        for (StackTraceElement[] stack : stacks) {
            assertTypedEntry("under torture", stack, null);
        }
    }
}
