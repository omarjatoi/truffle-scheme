package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.SandboxPolicy;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.dsl.Introspection;
import com.oracle.truffle.api.dsl.Introspection.SpecializationInfo;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;

import scheme.runtime.ForeignNodes;
import scheme.runtime.value.SClosure;

/**
 * The library {@code (graalvm polyglot)} (design.md D20): Scheme evaluates another language (GraalVM's
 * SimpleLanguage, {@code sl}, a test-only dependency), shares the polyglot bindings with it and with
 * Java, calls its functions and is called back, reads foreign arrays, objects and hashes through
 * explicit accessors, and reaches Java classes. Errors cross in both directions, and a continuation
 * can't cross a foreign frame.
 */
public class PolyglotTest {

    private static final String SCHEME = "scheme";
    private static final String SL = "sl";
    private static final String IMPORT = "(import (scheme base) (scheme write) (graalvm polyglot))\n";

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    /** A context in which Scheme may use SL, the polyglot bindings and Java. */
    private static Context context() {
        return Context.newBuilder(SCHEME, SL).out(new ByteArrayOutputStream()).allowPolyglotAccess(PolyglotAccess.ALL).allowHostAccess(HostAccess.ALL).allowHostClassLookup(
                        name -> !name.isEmpty()).build();
    }

    /** Runs {@code body} as a program that imports the library: its last value. */
    private static Value run(Context context, String body) {
        return context.eval(SCHEME, IMPORT + body);
    }

    private static String show(Context context, String body) {
        return run(context, body).toString();
    }

    /** SL code that defines {@code twice(g)}, which calls g(20) and adds one, and returns it. */
    private static final String SL_CALLER = "function callWith20(g) { return g(20) + 1; } function main() { return callWith20; }";

    // ---------------------------------------------------------------------------------------------
    // Evaluating other languages

    @Test
    public void evaluatesAnotherLanguage() {
        try (Context context = context()) {
            assertEquals(42, run(context, "(polyglot-eval \"sl\" \"function main() { return 40 + 2; }\")").asInt());
            // Strings and numbers come in as Scheme strings and numbers (D10's boundary).
            assertEquals("(#t #t \"hi!\" 3)", show(context, """
                            (let ((s (polyglot-eval 'sl "function main() { return \\"hi\\"; }"))
                                  (n (polyglot-eval "sl" "function main() { return 1 + 2; }")))
                              (list (string? s) (exact-integer? n) (string-append s "!") n))
                            """));
            assertEquals("(#t #t #f)", show(context, "(list (polyglot-language-available? \"sl\") (polyglot-language-available? 'scheme) (polyglot-language-available? \"js\"))"));
            // A language that isn't there is a Scheme error that names it and the ones that are.
            assertEquals("(\"js\" (\"scheme\" \"sl\"))", show(context, """
                            (guard (e ((error-object? e) (error-object-irritants e)))
                              (polyglot-eval "js" "1"))
                            """));
            // A syntax error of the other language is its own exception, raised in Scheme.
            assertEquals("#t", show(context, "(guard (e ((error-object? e) (foreign? e))) (polyglot-eval \"sl\" \"function (\"))"));
        }
    }

    @Test
    public void evaluatesAFile() throws IOException {
        Path file = folder.newFile("answer.sl").toPath();
        Files.writeString(file, "function main() { return 6 * 7; }");
        try (Context context = Context.newBuilder(SCHEME, SL).out(new ByteArrayOutputStream()).allowPolyglotAccess(PolyglotAccess.ALL).allowIO(IOAccess.ALL).build()) {
            assertEquals(42, run(context, "(polyglot-eval-file \"sl\" \"" + file + "\")").asInt());
            assertEquals("#t", show(context, "(guard (e ((file-error? e) #t)) (polyglot-eval-file \"sl\" \"" + file + ".missing\"))"));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The polyglot bindings

    @Test
    public void sharesThePolyglotBindings() {
        try (Context context = context()) {
            context.getPolyglotBindings().putMember("fromJava", 41);
            assertEquals(42, run(context, "(+ 1 (polyglot-import \"fromJava\"))").asInt());
            assertEquals("#f", show(context, "(polyglot-import 'nothing-here)"));
            // A default tells an exported #f from no binding (D20, "Decided on review").
            assertEquals("(none #f #t #f)", show(context, """
                            (polyglot-export "no" #f)
                            (define unbound (list 'unbound))
                            (list (polyglot-import 'nothing-here 'none)
                                  (polyglot-import "no" unbound)
                                  (eq? (polyglot-import "nothing-here" unbound) unbound)
                                  (polyglot-import "no"))
                            """));
            // What Scheme exports, Java and SL import.
            run(context, "(polyglot-export \"fromScheme\" (vector 1 2 3)) (polyglot-export 'square (lambda (x) (* x x)))");
            assertEquals(3, context.getPolyglotBindings().getMember("fromScheme").getArraySize());
            assertEquals(49, context.getPolyglotBindings().getMember("square").execute(7).asInt());
            assertEquals(81, context.eval(SL, "function main() { return import(\"square\")(9); }").asInt());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Foreign procedures and callbacks

    @Test
    public void foreignFunctionsAreProcedures() {
        try (Context context = context()) {
            assertEquals("(#t #t #f 41 41 (11 21))", show(context, """
                            (define call-with-20 (polyglot-eval "sl" "%s"))
                            (list (procedure? call-with-20)
                                  (foreign? call-with-20)
                                  (vector? call-with-20)
                                  ;; A Scheme procedure goes to SL, which calls it back.
                                  (call-with-20 (lambda (x) (* x 2)))
                                  (apply call-with-20 (list (lambda (x) (+ x x))))
                                  (map call-with-20 (list (lambda (x) (- x 10)) (lambda (x) x))))
                            """.formatted(SL_CALLER)));
            // A tail call of it, from a loop, in constant space.
            assertEquals(21, run(context, """
                            (define f (polyglot-eval "sl" "%s"))
                            (define (loop n) (if (= n 0) (f (lambda (x) x)) (loop (- n 1))))
                            (loop 100000)
                            """.formatted(SL_CALLER)).asInt());
        }
    }

    @Test
    public void aSchemeProcedureRoundTrips() {
        try (Context context = context()) {
            // Java gets a Scheme procedure, hands it to SL, which calls it: Value round trips.
            Value square = run(context, "(lambda (x) (* x x))");
            Value callWith20 = context.eval(SL, SL_CALLER);
            assertEquals(401, callWith20.execute(square).asInt());
            // And Scheme gets the SL function from Java.
            Value apply = run(context, "(lambda (f g) (f g))");
            assertEquals(401, apply.execute(callWith20, square).asInt());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Continuations across foreign frames

    @Test
    public void aContinuationCantEscapeThroughAForeignFrame() {
        try (Context context = context()) {
            // k was captured outside the SL call; invoking it in the callback would escape through
            // SL's frame: an error, raised in the callback, which leaves through SL and is raised
            // again at the call: the guard outside catches it. The callback's after-thunk runs as the
            // error leaves it.
            assertEquals("(\"continuation would escape through a foreign frame: it was captured outside the foreign call that this code runs in\" (before after))",
                            show(context, """
                                            (define f (polyglot-eval "sl" "%s"))
                                            (define log '())
                                            (define message
                                              (guard (e ((error-object? e) (error-object-message e)))
                                                (call/cc
                                                  (lambda (k)
                                                    (f (lambda (x)
                                                         (dynamic-wind
                                                           (lambda () (set! log (cons 'before log)))
                                                           (lambda () (k 1))
                                                           (lambda () (set! log (cons 'after log))))))))))
                                            (list message (reverse log))
                                            """.formatted(SL_CALLER)));
            // Escaping inside the callback, to a continuation captured in it, is fine.
            assertEquals(6, run(context, """
                            (define f (polyglot-eval "sl" "%s"))
                            (f (lambda (x) (call/cc (lambda (k) (+ 1 (k (/ x 4)))))))
                            """.formatted(SL_CALLER)).asInt());
        }
    }

    @Test
    public void aCallbacksContinuationCantResumeAfterItsForeignCallReturned() {
        try (Context context = context()) {
            assertEquals("(21 \"continuation would resume a foreign frame that has returned: it was captured in a callback from foreign code\")", show(context, """
                            (define f (polyglot-eval "sl" "%s"))
                            (define saved #f)
                            (define first (f (lambda (x) (call/cc (lambda (k) (set! saved k) x)))))
                            (list first
                                  (guard (e ((error-object? e) (error-object-message e)))
                                    (saved 5)))
                            """.formatted(SL_CALLER)));
        }
    }

    @Test
    public void aGeneratorWorksWhenAnotherLanguageDrivesIt() {
        try (Context context = context()) {
            // Each SL call of next is a callback; each resumes the loop that the one before it left,
            // as between an embedder's calls (D10): the same number of foreign calls is on the stack.
            assertEquals(321, run(context, """
                            (define (make-generator items)
                              (define return #f)
                              (define resume #f)
                              (lambda ()
                                (call/cc
                                  (lambda (r)
                                    (set! return r)
                                    (if resume
                                        (resume #f)
                                        (begin
                                          (for-each (lambda (x) (call/cc (lambda (k) (set! resume k) (return x))))
                                                    items)
                                          (return 'done)))))))
                            (define drive (polyglot-eval "sl" "function drive(next) { return next() + next() * 10 + next() * 100; } function main() { return drive; }"))
                            (drive (make-generator '(1 2 3)))
                            """).asInt());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Errors in both directions

    @Test
    public void foreignExceptionsAreRaisedInScheme() {
        try (Context context = context()) {
            assertEquals("(#t #t #t ())", show(context, """
                            (define bad (polyglot-eval "sl" "function bad() { return 1 + new(); } function main() { return bad; }"))
                            (guard (e ((error-object? e)
                                       (list (foreign? e)
                                             (string? (error-object-message e))
                                             (< 0 (string-length (error-object-message e)))
                                             (error-object-irritants e))))
                              (bad))
                            """));
            // A Java exception, through host interop.
            assertEquals("#t", show(context, """
                            (define list (foreign-new (java-type "java.util.ArrayList")))
                            (guard (e ((error-object? e)
                                       (let ((m (error-object-message e)))
                                         (and (foreign? e) (string? m)))))
                              (foreign-invoke list 'get 99))
                            """));
            // Uncaught, it leaves Scheme as SL's own exception.
            Value callBad = run(context, """
                            (define bad (polyglot-eval "sl" "function bad() { return 1 + new(); } function main() { return bad; }"))
                            (lambda () (bad))
                            """);
            try {
                callBad.execute();
                fail("bad returned");
            } catch (PolyglotException e) {
                assertTrue(e.getMessage(), e.isGuestException() && e.getMessage().startsWith("Type error"));
            }
        }
    }

    @Test
    public void aCallbacksRaiseCrossesTheForeignFrame() {
        try (Context context = context()) {
            // What a callback raises, and doesn't handle, is raised again at the SL call: the
            // same object.
            assertEquals("(#t boom \"bad\" (20))", show(context, """
                            (define f (polyglot-eval "sl" "%s"))
                            (define thing (list 'thing))
                            (list (eq? thing (guard (e (#t e)) (f (lambda (x) (raise thing)))))
                                  (guard (e ((symbol? e) e)) (f (lambda (x) (raise 'boom))))
                                  (guard (e ((error-object? e) (error-object-message e))) (f (lambda (x) (error "bad" x))))
                                  (guard (e ((error-object? e) (error-object-irritants e))) (f (lambda (x) (error "bad" x)))))
                            """.formatted(SL_CALLER)));
            // The handlers outside aren't the callback's: a raise-continuable crosses the frame, and
            // the handler outside gets it at the call.
            assertEquals("(handled c)", show(context, """
                            (define f (polyglot-eval "sl" "%s"))
                            (call/cc
                              (lambda (k)
                                (with-exception-handler
                                  (lambda (e) (k (list 'handled e)))
                                  (lambda () (f (lambda (x) (raise-continuable 'c)))))))
                            """.formatted(SL_CALLER)));
            // Inside the callback, its own handlers work as usual.
            assertEquals(43, run(context, """
                            (define f (polyglot-eval "sl" "%s"))
                            (f (lambda (x) (with-exception-handler (lambda (e) 22) (lambda () (+ (raise-continuable 'c) x)))))
                            """.formatted(SL_CALLER)).asInt());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Foreign values

    @Test
    public void foreignObjectsHaveMembers() {
        try (Context context = context()) {
            assertEquals("(5 6 (\"x\" \"y\") #f #f #t)", show(context, """
                            (define o (polyglot-eval "sl" "function main() { o = new(); o.x = 5; return o; }"))
                            (foreign-set! o "y" 6)
                            (list (foreign-ref o 'x) (foreign-ref o "y") (foreign-keys o) (pair? o) (vector? o) (foreign? o))
                            """));
            assertEquals("#t", show(context, """
                            (define o (polyglot-eval "sl" "function main() { return new(); }"))
                            (guard (e ((error-object? e) #t)) (foreign-ref o 'missing))
                            """));
        }
    }

    @Test
    public void javaArraysListsAndMaps() {
        try (Context context = context()) {
            Value probe = run(context, """
                            (lambda (a nothing)
                              (foreign-set! a 0 10)
                              (list (foreign-length a) (foreign-ref a 0) (foreign->vector a) (foreign->list a)
                                    (vector? a) (foreign-null? nothing) (foreign-null? a)
                                    (guard (e ((error-object? e) 'not-a-vector)) (vector-ref a 0))))
                            """);
            int[] numbers = {1, 2, 3};
            assertEquals("(3 10 #(10 2 3) (10 2 3) #f #t #f not-a-vector)", probe.execute(numbers, null).toString());
            assertEquals(10, numbers[0]);
            assertEquals("(2 (1 \"two\") #t #t #f 1 (\"a\") 1 #t)", show(context, """
                            (define ArrayList (java-type "java.util.ArrayList"))
                            (define l (foreign-new ArrayList))
                            (foreign-invoke l 'add 1)
                            (foreign-invoke l "add" "two")
                            (define m (foreign-new (java-type "java.util.HashMap")))
                            (foreign-set! m "a" 1)
                            (list (foreign-length l) (foreign->list l) (string? (foreign-ref l 1))
                                  (foreign-instance? l ArrayList) (foreign-instance? l (java-type "java.util.Map"))
                                  (foreign-ref m "a") (foreign-keys m) (foreign-length m)
                                  (foreign-null? (foreign-invoke m 'get "zzz")))
                            """));
            // Static members, and a Java big integer, which comes in as an exact integer.
            assertEquals("(2147483647 7 #t 1234567890123456789012345678901)", show(context, """
                            (define big (foreign-new (java-type "java.math.BigInteger") "1234567890123456789012345678900"))
                            (list (foreign-ref (java-type "java.lang.Integer") 'MAX_VALUE)
                                  (foreign-invoke (java-type "java.lang.Math") 'max 3 7)
                                  (exact-integer? big)
                                  (+ big 1))
                            """));
        }
    }

    @Test
    public void javaObjectsKeepTheirIdentity() {
        try (Context context = context()) {
            List<Object> list = new ArrayList<>();
            Value same = run(context, "(lambda (a b) (eq? a b))");
            assertTrue(same.execute(list, list).asBoolean());
            Value back = run(context, "(lambda (x) (foreign-invoke x 'add 1) x)");
            assertSame(list, back.execute(list).asHostObject());
            assertEquals("[1]", list.toString());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Permissions and the sandbox

    @Test
    public void theEmbedderDecides() {
        try (Context context = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).build()) {
            assertEquals("(#f \"polyglot-eval: language not available (the ones that are follow it)\")", show(context, """
                            (list (polyglot-language-available? "sl")
                                  (guard (e ((error-object? e) (error-object-message e))) (polyglot-eval "sl" "")))
                            """));
            String bindings = show(context, "(guard (e ((error-object? e) (error-object-message e))) (polyglot-import \"x\"))");
            assertTrue(bindings, bindings.contains("allowPolyglotAccess"));
            String lookup = show(context, "(guard (e ((error-object? e) (error-object-message e))) (java-type \"java.util.ArrayList\"))");
            assertTrue(lookup, lookup.contains("allowHostClassLookup"));
        }
        // A class the filter doesn't allow.
        try (Context context = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).allowHostClassLookup(name -> name.equals("java.util.ArrayList")).build()) {
            assertEquals("(#t #t)", show(context, """
                            (list (foreign? (java-type "java.util.ArrayList"))
                                  (guard (e ((error-object? e) #t)) (java-type "java.io.File")))
                            """));
        }
    }

    @Test
    public void javaTypeUnderASandboxIsTheEmbeddersHostAccess() {
        // No sandbox policy restricts host class lookup (D20): under CONSTRAINED, java-type finds what
        // the class filter allows, and HostAccess.CONSTRAINED, the policy's default, gives it only the
        // members annotated @HostAccess.Export, none of a JDK class's.
        try (Engine engine = Engine.newBuilder(SCHEME).sandbox(SandboxPolicy.CONSTRAINED).useSystemProperties(false).out(OutputStream.nullOutputStream()).err(
                        OutputStream.nullOutputStream()).build();
                        Context context = Context.newBuilder(SCHEME).engine(engine).sandbox(SandboxPolicy.CONSTRAINED).out(new ByteArrayOutputStream()).err(
                                        new ByteArrayOutputStream()).allowHostClassLookup(name -> name.startsWith("java.util.")).build()) {
            Value probe = run(context, """
                            (lambda (items)
                              (define (message thunk) (guard (e ((error-object? e) (error-object-message e))) (thunk)))
                              (define ArrayList (java-type "java.util.ArrayList"))
                              (list (foreign? ArrayList)
                                    (foreign-instance? items ArrayList)
                                    (foreign-instance? items (java-type "java.util.HashMap"))
                                    (message (lambda () (foreign-new ArrayList)))
                                    (message (lambda () (foreign-invoke items 'size)))
                                    (message (lambda () (java-type "java.io.File")))))
                            """);
            assertEquals("(#t #t #f \"foreign-new: not supported by this foreign value\" \"foreign-invoke: no such member: size\" \"java-type: no such class, or not accessible: Access to host class java.io.File is not allowed.\")",
                            probe.execute(new ArrayList<>(List.of(1))).toString());
        }
    }

    @Test
    public void onlyAProgramThatImportsItSeesIt() {
        try (Context context = context()) {
            Value bindings = context.getBindings(SCHEME);
            assertFalse(bindings.hasMember("polyglot-eval"));
            assertFalse(bindings.hasMember("foreign-ref"));
            try {
                context.eval(SCHEME, "(import (scheme base)) (foreign? 1)");
                fail("foreign? was bound");
            } catch (PolyglotException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("foreign?"));
            }
            assertEquals("ok", context.eval(SCHEME, "(import (scheme base) (prefix (graalvm polyglot) pg:)) (if (pg:foreign? 1) 'no 'ok)").toString());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Foreign access in compiled code (design.md D20)

    /** SL's add and callBack, and Scheme procedures that call them in loops, as bench/polyglot/foreign.scm. */
    private static final String FOREIGN_LOOPS = IMPORT + """
                    (define sl
                      (polyglot-eval "sl" "
                    function add(a, b) { return a + b; }
                    function callBack(f, n) { i = 0; s = 0; while (i < n) { s = s + f(i); i = i + 1; } return s; }
                    function main() { o = new(); o.add = add; o.callBack = callBack; return o; }"))
                    (define add (foreign-ref sl 'add))
                    (define call-back (foreign-ref sl 'callBack))
                    (define (calls n)
                      (let loop ((i 0) (s 0))
                        (if (= i n) s (loop (+ i 1) (add s 1)))))
                    (define (add-one x) (add x 1))
                    (define (tail-calls n)
                      (let loop ((i 0) (s 0))
                        (if (= i n) s (loop (+ i 1) (add-one s)))))
                    (define (callbacks n)
                      (call-back (lambda (i) (+ i 1)) n))
                    """;

    /**
     * Loops of the foreign-* procedures over an SL object and Java's arrays, lists, maps, fields and
     * methods, as bench/polyglot/foreign.scm's elements and members: {@code (accessors n)} is
     * {@code 15 * n}.
     */
    private static final String FOREIGN_ACCESSORS = IMPORT + """
                    (define o (polyglot-eval "sl" "function main() { o = new(); o.x = 5; return o; }"))
                    (define array (foreign-invoke (foreign-invoke (java-type "java.util.stream.IntStream") 'range 0 10) 'toArray))
                    (define ArrayList (java-type "java.util.ArrayList"))
                    (define l (foreign-new ArrayList))
                    (foreign-invoke l 'add 1)
                    (define m (foreign-new (java-type "java.util.HashMap")))
                    (foreign-set! m "a" 1)
                    (define tokenizer
                      (foreign-new (java-type "java.io.StreamTokenizer") (foreign-new (java-type "java.io.StringReader") "")))
                    (define (accessors n)
                      (let loop ((i 0) (s 0))
                        (if (= i n)
                            s
                            (begin
                              (foreign-set! o 'x (+ (foreign-ref o 'x) 1))
                              (foreign-set! array 1 (foreign-ref array 2))
                              (loop (+ i 1)
                                    (+ s
                                       (foreign-ref array 1)                                 ; 2
                                       (foreign-length array)                                ; 10
                                       (foreign-ref l 0)                                     ; 1
                                       (foreign-invoke l 'size)                              ; 1
                                       (foreign-ref m "a")                                   ; 1
                                       (foreign-ref tokenizer 'ttype)                        ; -4
                                       (if (foreign-instance? l ArrayList) 1 0)              ; 1
                                       (if (foreign-null? (foreign-invoke m 'get "b")) 1 0)  ; 1
                                       (vector-length (foreign->vector l))                   ; 1
                                       (length (foreign-keys m))))))))                       ; 1
                    """;

    @Test
    public void foreignCallsUseTheSitesInteropCaches() {
        try (Context context = context()) {
            ResultProbe probe = context.getEngine().getInstruments().get(ResultProbe.ID).lookup(ResultProbe.class);
            // The call site in calls' loop, and the trampoline of the one in tail-calls' loop
            // (add-one's tail call of add comes back to it), each hold a foreign call node with a
            // cached library for SL's function: no uncached interop.
            for (String name : List.of("calls", "tail-calls")) {
                String program = FOREIGN_LOOPS + "(unless (= (%s 10) 10) (error \"wrong\")) %s".formatted(name, name);
                context.eval(Source.newBuilder(SCHEME, program, ResultProbe.SOURCE).buildLiteral());
                List<Object> libraries = cachedLibraries((SClosure) probe.last, ForeignNodes.CallNode.class);
                assertEquals(name, 1, libraries.size());
                InteropLibrary library = (InteropLibrary) libraries.get(0);
                assertTrue(name, library.isAdoptable());
            }
        }
    }

    /** The interop libraries cached by the nodes of class {@code type} under the instructions of {@code procedure}'s root. */
    private static List<Object> cachedLibraries(SClosure procedure, Class<? extends Node> type) {
        BytecodeRootNode root = (BytecodeRootNode) procedure.callTarget.getRootNode();
        List<Object> libraries = new ArrayList<>();
        for (Instruction instruction : root.getBytecodeNode().getInstructions()) {
            for (Instruction.Argument argument : instruction.getArguments()) {
                if (argument.getKind() != Instruction.Argument.Kind.NODE_PROFILE || argument.asCachedNode() == null) {
                    continue;
                }
                for (Node node : NodeUtil.findAllNodeInstances(argument.asCachedNode(), type)) {
                    for (SpecializationInfo s : Introspection.getSpecializations(node)) {
                        for (int i = 0; i < s.getInstances(); i++) {
                            for (Object cached : s.getCachedData(i)) {
                                if (cached instanceof InteropLibrary) {
                                    libraries.add(cached);
                                }
                            }
                        }
                    }
                }
            }
        }
        return libraries;
    }

    @Test
    public void foreignAccessorsUseTheirInteropCaches() {
        try (Context context = context()) {
            ResultProbe probe = context.getEngine().getInstruments().get(ResultProbe.ID).lookup(ResultProbe.class);
            String program = FOREIGN_ACCESSORS + "(unless (= (accessors 10) 150) (error \"wrong\")) accessors";
            context.eval(Source.newBuilder(SCHEME, program, ResultProbe.SOURCE).buildLiteral());
            // Each foreign-ref and foreign-set! in the loop runs in place, with a node of its call
            // site's own and a cached library for the value it reads or writes: no uncached interop.
            List<Object> reads = cachedLibraries((SClosure) probe.last, ForeignNodes.ReadNode.class);
            List<Object> writes = cachedLibraries((SClosure) probe.last, ForeignNodes.WriteNode.class);
            assertEquals(6, reads.size());
            assertEquals(2, writes.size());
            for (Object library : reads) {
                assertTrue(((InteropLibrary) library).isAdoptable());
            }
            for (Object library : writes) {
                assertTrue(((InteropLibrary) library).isAdoptable());
            }
        }
    }

    @Test
    public void foreignAccessCompilesWithoutPerformanceWarnings() throws IOException {
        Path log = folder.newFile("engine.log").toPath();
        Context.Builder builder = Context.newBuilder(SCHEME, SL).out(new ByteArrayOutputStream()).allowPolyglotAccess(PolyglotAccess.ALL).allowHostAccess(HostAccess.ALL).allowHostClassLookup(
                        name -> !name.isEmpty()).allowExperimentalOptions(true).option("log.file", log.toString());
        builder.option("engine.Compilation", "true").option("engine.CompileImmediately", "false").option("engine.BackgroundCompilation", "false");
        builder.option("engine.SingleTierCompilationThreshold", "100").option("engine.FirstTierCompilationThreshold", "100");
        builder.option("engine.LastTierCompilationThreshold", "500");
        builder.option("compiler.TracePerformanceWarnings", "all").option("engine.TraceCompilation", "true");
        Context context;
        try {
            context = builder.build();
        } catch (IllegalArgumentException e) {
            Assume.assumeNoException("no optimizing Truffle runtime", e);
            throw e;
        }
        try (Context c = context) {
            assertEquals(2000 * (100 + 100 + 5050), c.eval(SCHEME, FOREIGN_LOOPS + """
                            (let loop ((i 0) (s 0))
                              (if (= i 2000)
                                  s
                                  (loop (+ i 1) (+ s (calls 100) (tail-calls 100) (callbacks 100)))))
                            """).asInt());
            assertEquals(2000 * 15 * 100, c.eval(SCHEME, FOREIGN_ACCESSORS + """
                            (let loop ((i 0) (s 0))
                              (if (= i 2000) s (loop (+ i 1) (+ s (accessors 100)))))
                            """).asInt());
        }
        String engineLog = Files.readString(log);
        assertTrue(engineLog, engineLog.contains("opt done") && engineLog.contains("calls") && engineLog.contains("tail-calls") && engineLog.contains("accessors"));
        assertFalse(engineLog, engineLog.contains("perf warn"));
    }

    /**
     * The library under {@code ISOLATED} and {@code UNTRUSTED}, which run the language in
     * //:scheme_isolate's polyglot isolate (design.md D10, "ISOLATED and UNTRUSTED"; D20, "Permissions
     * and the sandbox"). Run only when named, as {@code //tests:unit_isolate_PolyglotTest}, like
     * {@code SandboxTest.Isolated}: the library is a native-image build. The isolate holds Scheme
     * alone, so SL, on this JVM's module path, is not a language Scheme can reach there.
     */
    public static class Isolated {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        /** A context under ISOLATED, with the limits it requires and every polyglot permission. */
        private Context.Builder isolated() {
            return Context.newBuilder(SCHEME).sandbox(SandboxPolicy.ISOLATED).out(out).err(out).option("engine.MaxIsolateMemory", "256MB").option("sandbox.MaxCPUTime",
                            "20s").allowPolyglotAccess(PolyglotAccess.ALL).allowHostClassLookup(name -> !name.isEmpty());
        }

        /** A context under UNTRUSTED, with the limits it requires and every polyglot permission. */
        private Context.Builder untrusted() {
            return Context.newBuilder(SCHEME) //
                            .sandbox(SandboxPolicy.UNTRUSTED) //
                            .in(new ByteArrayInputStream(new byte[0])) //
                            .out(out) //
                            .err(out) //
                            .option("engine.MaxIsolateMemory", "512MB") //
                            .option("sandbox.MaxHeapMemory", "128MB") //
                            .option("sandbox.MaxCPUTime", "20s") //
                            .option("sandbox.MaxASTDepth", "50") //
                            .option("sandbox.MaxThreads", "1") //
                            .option("sandbox.MaxOutputStreamSize", "1MB") //
                            .option("sandbox.MaxErrorStreamSize", "1MB") //
                            .allowPolyglotAccess(PolyglotAccess.ALL) //
                            .allowHostClassLookup(name -> !name.isEmpty());
        }

        /** The message of what {@code body} raises, or its value. */
        private static String message(Context context, String body) {
            return show(context, "(guard (e ((error-object? e) (error-object-message e))) " + body + ")");
        }

        @Test
        public void polyglotEvalReachesOnlyTheLanguagesInTheIsolate() {
            for (Context.Builder builder : List.of(isolated(), untrusted())) {
                try (Context context = builder.build()) {
                    // Scheme itself is there; SL, on this JVM's module path, is not.
                    assertEquals("(#f #t (\"sl\" (\"scheme\")) 3)", show(context, """
                                    (list (polyglot-language-available? "sl")
                                          (polyglot-language-available? "scheme")
                                          (guard (e ((error-object? e) (error-object-irritants e))) (polyglot-eval "sl" "function main() { return 1; }"))
                                          (guard (e ((error-object? e) (error-object-message e))) (polyglot-eval "scheme" "(+ 1 2)")))
                                    """));
                }
            }
            // A context can't permit SL: the isolate library is Scheme's alone.
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Context.newBuilder(SCHEME, SL).sandbox(SandboxPolicy.ISOLATED).out(out).err(out).option(
                            "engine.MaxIsolateMemory",
                            "256MB").option("sandbox.MaxCPUTime", "20s").build().close());
            assertTrue(e.getMessage(), e.getMessage().contains("No native isolate library is available for the requested language(s) [scheme, sl]"));
        }

        @Test
        public void javaTypeLooksTheClassUpInTheHostUnderBothPolicies() {
            // The lookup goes through the isolate to this JVM (D20): the class is there, with what the
            // policy's HostAccess exports of it, none of a JDK class's members.
            for (Context.Builder builder : List.of(isolated(), untrusted())) {
                try (Context context = builder.build()) {
                    assertEquals("(#t \"foreign-new: not supported by this foreign value\")", show(context, """
                                    (let ((ArrayList (java-type "java.util.ArrayList")))
                                      (list (foreign? ArrayList)
                                            (guard (e ((error-object? e) (error-object-message e))) (foreign-new ArrayList))))
                                    """));
                }
            }
        }

        @Test
        public void javaTypeWorksInAnIsolateUnderTrusted() {
            // engine.SpawnIsolate runs the language in the isolate under any policy. The isolate is a
            // native image without reflection metadata, but the class is this JVM's, reached through
            // the isolate, so it has all its members under HostAccess.ALL; a Java object handed in
            // works the same way.
            try (Context context = Context.newBuilder(SCHEME).out(out).err(out).option("engine.SpawnIsolate", "true").allowHostAccess(HostAccess.ALL).allowHostClassLookup(
                            name -> !name.isEmpty()).build()) {
                assertEquals("(#t 2 (1 \"two\") 7)", show(context, """
                                (let ((l (foreign-new (java-type "java.util.ArrayList"))))
                                  (foreign-invoke l 'add 1)
                                  (foreign-invoke l 'add "two")
                                  (list (foreign? l) (foreign-length l) (foreign->list l) (foreign-invoke (java-type "java.lang.Math") 'max 3 7)))
                                """));
                List<Object> list = new ArrayList<>();
                Value add = run(context, "(lambda (l) (foreign-invoke l 'add \"x\") (list (foreign-length l) (foreign->list l)))");
                assertEquals("(1 (\"x\"))", add.execute(list).toString());
                assertEquals("[x]", list.toString());
            }
        }

        @Test
        public void thePolyglotBindingsCrossTheIsolate() {
            for (Context.Builder builder : List.of(isolated(), untrusted())) {
                try (Context context = builder.build()) {
                    context.getPolyglotBindings().putMember("greeting", "Hello");
                    run(context, "(polyglot-export \"greet\" (lambda (name) (string-append (polyglot-import \"greeting\") \", \" name \"!\")))");
                    assertEquals("Hello, Ada!", context.getPolyglotBindings().getMember("greet").execute("Ada").asString());
                    // (A boolean from the isolate is a Java one.)
                    assertFalse(run(context, "(polyglot-import \"nothing\")").asBoolean());
                }
            }
        }

        @Test
        public void hostValuesAreForeignValuesInTheIsolate() {
            for (Context.Builder builder : List.of(isolated(), untrusted())) {
                try (Context context = builder.build()) {
                    Value probe = run(context, """
                                    (lambda (object array twice fail items)
                                      (list (foreign? object) (foreign-ref object "x") (foreign-keys object)
                                            (foreign-length array) (foreign->list array) (procedure? twice) (twice 21)
                                            (twice (lambda (x) (* x 10)))
                                            (guard (e ((error-object? e) (list (foreign? e) (error-object-message e)))) (fail))
                                            (guard (e ((error-object? e) (error-object-message e))) (foreign-length items))))
                                    """);
                    ProxyExecutable twice = args -> args[0].canExecute() ? args[0].execute(2) : args[0].asInt() * 2;
                    ProxyExecutable fail = args -> {
                        throw new IllegalStateException("from the host, with " + args.length + " arguments");
                    };
                    // A proxy's members and elements, a host function called and calling back, a host
                    // exception raised in Scheme as itself; but a Java list is no array: the
                    // policy's HostAccess has no list access.
                    assertEquals("(#t 5 (\"x\") 3 (1 2 3) #t 42 20 (#t \"from the host, with 0 arguments\") \"foreign-length: not supported by this foreign value\")", probe.execute(
                                    ProxyObject.fromMap(Map.of("x", 5)),
                                    ProxyArray.fromArray(1, 2, 3), twice, fail, List.of(1, 2)).toString());
                }
            }
        }

        @Test
        public void aContinuationCantEscapeThroughAHostFrame() {
            for (Context.Builder builder : List.of(isolated(), untrusted())) {
                try (Context context = builder.build()) {
                    Value probe = run(context, """
                                    (lambda (call)
                                      (guard (e ((error-object? e) (error-object-message e)))
                                        (call/cc (lambda (k) (call (lambda () (k 1)))))))
                                    """);
                    ProxyExecutable call = args -> args[0].execute();
                    assertEquals("\"continuation would escape through a foreign frame: it was captured outside the foreign call that this code runs in\"", probe.execute(call).toString());
                }
            }
        }
    }
}
