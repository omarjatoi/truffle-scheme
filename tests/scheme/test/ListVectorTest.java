package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import scheme.launcher.SchemeMain;

/** M4-03: the list and vector procedures of R7RS §6.4 and §6.8, and (scheme cxr). */
public class ListVectorTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static String write(String expr) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("scheme").out(out).build()) {
            context.eval("scheme", "(write " + expr + ")");
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Evaluates {@code expr} and reports whether it raised an error object. */
    private static String raises(String expr) {
        return write("(guard (e ((error-object? e) 'error)) " + expr + ")");
    }

    @Test
    public void listPredicate() {
        assertEquals("(#t #t #f #f #f)", write("(list (list? '(a b c)) (list? '()) (list? '(a . b)) (list? 3) (list? #(1)))"));
        assertEquals("#f", write("(let ((x (list 'a))) (set-cdr! x x) (list? x))"));
        assertEquals("#f", write("(let ((x (list 1 2 3 4 5))) (set-cdr! (cddddr x) (cdr x)) (list? x))"));
    }

    @Test
    public void makeListAndIndexing() {
        assertEquals("((3 3) () 2)", write("(list (make-list 2 3) (make-list 0) (length (make-list 2)))"));
        assertEquals("((d e) () c)", write("(list (list-tail '(a b c d e) 3) (list-tail '(a b) 2) (list-ref '(a b c d) 2))"));
        assertEquals("(0 x 2)", write("(let ((l (list 0 1 2))) (list-set! l 1 'x) l)"));
        assertEquals("error", raises("(list-ref '(a b) 2)"));
        assertEquals("error", raises("(list-tail '(a b) 3)"));
        assertEquals("error", raises("(list-ref '(a b) -1)"));
        assertEquals("error", raises("(make-list -1)"));
    }

    @Test
    public void listCopy() {
        assertEquals("((1 2 3) \"foo\" () (3 . 4))", write("(list (list-copy '(1 2 3)) (list-copy \"foo\") (list-copy '()) (list-copy '(3 . 4)))"));
        assertEquals("(#t #f)", write("(let* ((a '((x) y)) (b (list-copy a))) (list (eq? (car a) (car b)) (eq? (cdr a) (cdr b))))"));
    }

    @Test
    public void memberAndAssoc() {
        assertEquals("((a) c)", write("(member (list 'a) '(b (a) c))"));
        assertEquals("(4 5)", write("(member 3 '(1 4 5) <)"));
        assertEquals("#f", write("(member 9 '(1 2))"));
        assertEquals("((a))", write("(assoc (list 'a) '(((a)) ((b))))"));
        assertEquals("(2 4)", write("(assoc 2.0 '((1 1) (2 4) (3 9)) =)"));
        assertEquals("#f", write("(assoc 'z '((a 1)))"));
    }

    @Test
    public void cxr() {
        assertEquals("(1 2 3 4)", write("(let ((x '(((1) 2) 3 4))) (list (caaar x) (cadaar '((((0) 2)))) (cadr x) (cadddr '(0 1 2 4))))"));
        assertEquals("(5)", write("(cddddr '(1 2 3 4 5))"));
    }

    @Test
    public void vectorConversions() {
        assertEquals("((a b c) (b c) (b))", write("(list (vector->list #(a b c)) (vector->list #(a b c) 1) (vector->list #(a b c) 1 2))"));
        assertEquals("(#(#\\A #\\B) #(#\\B) \"bc\" \"b\")",
                        write("(list (string->vector \"AB\") (string->vector \"AB\" 1) (vector->string #(#\\a #\\b #\\c) 1) (vector->string #(#\\a #\\b #\\c) 1 2))"));
        assertEquals("\"λx\"", write("(vector->string (vector #\\λ #\\x))"));
        assertEquals("error", raises("(vector->list #(a b c) 2 1)"));
        assertEquals("error", raises("(vector->string #(1 2))"));
        assertEquals("error", raises("(string->vector \"abc\" 4)"));
    }

    @Test
    public void copyAppendFill() {
        assertEquals("(#(a b c) #(b) #t)", write("(let ((v #(a b c))) (list (vector-copy v) (vector-copy v 1 2) (not (eq? v (vector-copy v)))))"));
        assertEquals("#(a b c d e f)", write("(vector-append #(a b c) #(d e) #() #(f))"));
        assertEquals("#(1 2 x x 5)", write("(let ((v (vector 1 2 3 4 5))) (vector-fill! v 'x 2 4) v)"));
        assertEquals("#(1 1 2 4 5)", write("(let ((v (vector 1 2 3 4 5))) (vector-copy! v 1 v 0 2) v)"));
        assertEquals("#(2 3 3 4 5)", write("(let ((v (vector 1 2 3 4 5))) (vector-copy! v 0 v 1 3) v)"));
        assertEquals("error", raises("(vector-copy! (vector 1 2) 1 #(a b))"));
        assertEquals("error", raises("(vector-fill! #(1 2) 0)"));
        assertEquals("error", raises("(vector-copy! #(1 2) 0 #(a))"));
        assertEquals("error", raises("(vector-append #(1) '(2))"));
        assertEquals("#t", write("(begin (vector-set! (vector-copy #(1 2)) 0 'x) #t)"));
    }

    @Test
    public void vectorMapAndForEach() {
        assertEquals("#(b e h)", write("(vector-map cadr '#((a b) (d e) (g h)))"));
        assertEquals("#(5 7 9)", write("(vector-map + #(1 2 3) #(4 5 6 7))"));
        assertEquals("(4 1 0)", write("(let ((acc '())) (vector-for-each (lambda (x) (set! acc (cons (* x x) acc))) #(0 1 2)) acc)"));
        assertEquals("(3 5)", write("(let ((acc '())) (vector-for-each (lambda (x y) (set! acc (cons (+ x y) acc))) #(1 2) #(2 3 4)) (reverse acc))"));
    }

    /** R7RS §6.10: vector-map must not change an earlier result when its continuation is re-entered. */
    @Test
    public void vectorMapReentry() {
        assertEquals("(#(1 2) #(1 3))",
                        write("(let ((k #f) (results '())) (let ((v (vector-map (lambda (x) (if (= x 2) (call/cc (lambda (c) (set! k c) 2)) x)) #(1 2)))) " +
                                        "(set! results (cons v results)) (if (= (length results) 1) (k 3)) (reverse results)))"));
    }

    /** A circular list (1 2 3 1 2 3 ...) bound to c, and a circular alist bound to a, around expr. */
    private static String circular(String expr) {
        return "(let ((c (list 1 2 3)) (a (list (cons 1 'one) (cons 2 'two)))) (set-cdr! (cddr c) c) (set-cdr! (cdr a) a) " + expr + ")";
    }

    /** What expr raises, as (message irritants-are-the-circular-list?), or its value. */
    private static String raisesOnCircular(String expr) {
        return write(circular("(guard (e ((error-object? e) (list (error-object-message e) (eq? (car (error-object-irritants e)) c)))) " + expr + ")"));
    }

    /**
     * M7-06: R7RS §6.4 says these procedures take a list, which a circular list is not, so passing
     * one "is an error" (§1.3.2); we raise instead of looping forever (design.md D9), naming the
     * procedure and with the list as the irritant.
     */
    @Test
    public void circularListsRaise() {
        String[] procedures = {"length", "reverse", "list-copy", "list->vector", "list->string"};
        for (String procedure : procedures) {
            assertEquals(procedure, "(\"" + procedure + ": not a proper list\" #t)", raisesOnCircular("(" + procedure + " c)"));
        }
        assertEquals("(\"append: not a proper list\" #t)", raisesOnCircular("(append '(0) c '(4))"));
        assertEquals("(\"apply: not a proper list\" #t)", raisesOnCircular("(apply + 0 c)"));
        // Without the element, memq and friends would walk a circular list forever.
        for (String procedure : new String[]{"memq", "memv", "member"}) {
            assertEquals(procedure, "(\"" + procedure + ": not a proper list\" #t)", raisesOnCircular("(" + procedure + " 4 c)"));
        }
        assertEquals("(\"member: not a proper list\" #t)", raisesOnCircular("(member 4 c =)"));
        for (String procedure : new String[]{"assq", "assv", "assoc"}) {
            assertEquals(procedure, "#t", write(circular("(guard (e ((error-object? e) (eq? (car (error-object-irritants e)) a))) (" + procedure + " 3 a))")));
        }
        assertEquals("#t", write(circular("(guard (e ((error-object? e) (eq? (car (error-object-irritants e)) a))) (assoc 3 a =))")));
        // With it, they return the first sublist (or pair) that has it, as for any list.
        assertEquals("(2 3 1 2 3)", write(circular("(let loop ((l (memv 2 c)) (n 5) (acc '())) (if (= n 0) (reverse acc) (loop (cdr l) (- n 1) (cons (car l) acc))))")));
        assertEquals("((2 . two) (2 . two) (1 . one) (2 . two) #t #t)", write(circular("(list (assq 2 a) (assoc 2.0 a =) (assv 1 a) (assoc 2 a) (eq? (member 1 c) c) (eq? (memq 3 c) (cddr c)))")));
        // map and for-each stop at the shortest list, which may be the only finite one (§6.10)...
        assertEquals("((1 a) (2 b) (3 c) (1 d))", write(circular("(map list c '(a b c d))")));
        assertEquals("(1 3 2 1)", write(circular("(let ((acc '())) (for-each (lambda (x y) (set! acc (cons x acc))) c '(a b c d)) acc)")));
        // ... but it is an error if every list is circular.
        assertEquals("(\"map: every list is circular\" #t)", raisesOnCircular("(map (lambda (x) x) c)"));
        assertEquals("(\"for-each: every list is circular\" #t)", raisesOnCircular("(for-each (lambda (x y) x) c c)"));
        assertEquals("(\"map: every list is circular\" #t)", raisesOnCircular("(map + c (cdr c))"));
        // Bounded walks and list? need nothing new.
        assertEquals("(2 (3 1 2 3 1 2 3 1) #f)", write(circular(
                        "(list (list-ref c 100) (let loop ((l (list-tail c 101)) (n 8) (acc '())) (if (= n 0) (reverse acc) (loop (cdr l) (- n 1) (cons (car l) acc)))) (list? c))")));
        // A cycle that starts further in, and a long one.
        assertEquals("(\"length: not a proper list\" #t)", raisesOnCircular("(let ((l (list 0 1 2 3 4 5 6 7 8 9))) (set-cdr! (list-tail l 9) (list-tail l 4)) (set! c l) (length l))"));
        assertEquals("(\"reverse: not a proper list\" #t)", raisesOnCircular("(let ((l (make-list 100000 0))) (set-cdr! (list-tail l 99999) l) (set! c l) (reverse l))"));
        // An improper list is still an error, and proper ones work as before.
        assertEquals("error", raises("(length '(1 2 . 3))"));
        assertEquals("error", raises("(reverse '(1 . 2))"));
        assertEquals("(3 (3 2 1) 0 ())", write("(list (length '(a b c)) (reverse '(1 2 3)) (length '()) (reverse '()))"));
        assertEquals("((a b) #f (b c) #f (2 . b) #f)", write("(list (memq 'a '(a b)) (memq 'z '(a b)) (memv 'b '(a b c)) (memv 1 '(a)) (assv 2 '((1 . a) (2 . b))) (assq 'x '()))"));
    }

    /** What expr raises, as (message irritants), or its value. */
    private static String raisesWith(String expr) {
        return write("(guard (e ((error-object? e) (list (error-object-message e) (error-object-irritants e)))) " + expr + ")");
    }

    /**
     * The §6 audit's follow-up: an improper list is not a list either (R7RS §6.4: "all lists have
     * finite length and are terminated by the empty list"), and memq, memv and member take a list,
     * assq, assv and assoc an alist (§6.4), map and for-each lists (§6.10), so passing one is an
     * error (§1.3.2). Their walks raise "<who>: not a proper list" with the list as the irritant when
     * they reach a tail that is neither a pair nor (), as for a circular list (design.md D9), instead
     * of taking it for the end. Only a tail the walk reaches: an element found before it is found,
     * and map and for-each stop at the shortest list.
     */
    @Test
    public void improperListsRaise() {
        for (String procedure : new String[]{"memq", "memv", "member"}) {
            assertEquals(procedure, "(\"" + procedure + ": not a proper list\" ((a b . c)))", raisesWith("(" + procedure + " 'z '(a b . c))"));
            assertEquals(procedure, "(\"" + procedure + ": not a proper list\" (x))", raisesWith("(" + procedure + " 'z 'x)"));
            assertEquals(procedure, "(b . c)", write("(" + procedure + " 'b '(a b . c))"));
            assertEquals(procedure, "#f", write("(" + procedure + " 'z '())"));
        }
        for (String procedure : new String[]{"assq", "assv", "assoc"}) {
            assertEquals(procedure, "(\"" + procedure + ": not a proper list\" (((a . 1) (b . 2) . c)))", raisesWith("(" + procedure + " 'z '((a . 1) (b . 2) . c))"));
            assertEquals(procedure, "(\"" + procedure + ": not a proper list\" (5))", raisesWith("(" + procedure + " 'z 5)"));
            assertEquals(procedure, "(b . 2)", write("(" + procedure + " 'b '((a . 1) (b . 2) . c))"));
        }
        // With a predicate, member and assoc are a Scheme loop (lists-vectors.scm).
        assertEquals("(\"member: not a proper list\" ((1 2 . 3)))", raisesWith("(member 5 '(1 2 . 3) =)"));
        assertEquals("(2 . 3)", write("(member 2.0 '(1 2 . 3) =)"));
        assertEquals("(\"assoc: not a proper list\" (((1 . a) . 3)))", raisesWith("(assoc 5 '((1 . a) . 3) =)"));
        assertEquals("(1 . a)", write("(assoc 1.0 '((1 . a) . 3) =)"));
        // A call the compiler turns into comparisons with each datum (a quoted proper list, plan.md
        // M8-05, M9-05) is unaffected; with a quoted improper list it calls the primitive, which raises.
        assertEquals("((b c) (b . 2) #f #f)", write("(let ((k 'b) (z 'z)) (list (memq k '(a b c)) (assv k '((a . 1) (b . 2))) (memv z '(a b c)) (assq z '((a . 1)))))"));
        assertEquals("(\"memv: not a proper list\" ((a b . c)))", raisesWith("(let ((k 'z)) (memv k '(a b . c)))"));
        assertEquals("(\"assq: not a proper list\" (((a . 1) . c)))", raisesWith("(let ((k 'z)) (assq k '((a . 1) . c)))"));
        // map and for-each, with one list and with several: the irritant is the list whose tail the
        // walk reached.
        assertEquals("(\"map: not a proper list\" (((1) (2) . 3)))", raisesWith("(map car '((1) (2) . 3))"));
        assertEquals("(\"map: not a proper list\" (5))", raisesWith("(map car 5)"));
        assertEquals("(\"map: not a proper list\" ((10 20 . 30)))", raisesWith("(map + '(1 2 3) '(10 20 . 30))"));
        assertEquals("(\"map: not a proper list\" ((1 . 2)))", raisesWith("(map + '(1 . 2) '(10 20 30))"));
        assertEquals("(\"map: not a proper list\" ((a b . c)))", raisesWith("(map list '(1 2) '(a b . c))"));
        assertEquals("(\"for-each: not a proper list\" (((1) . 3)))", raisesWith("(for-each car '((1) . 3))"));
        assertEquals("(\"for-each: not a proper list\" (x))", raisesWith("(for-each car 'x)"));
        assertEquals("(\"for-each: not a proper list\" ((10 . 30)))", raisesWith("(for-each + '(1 2 3) '(10 . 30))"));
        // f has run for the elements before the tail, in order.
        assertEquals("(2 1)", write("(let ((acc '())) (guard (e (#t acc)) (for-each (lambda (x) (set! acc (cons x acc))) '(1 2 . 3))))"));
        // The shortest list ends first: the other's improper tail is never reached.
        assertEquals("(11 22)", write("(map + '(1 2) '(10 20 30 . 40))"));
        assertEquals("(1 2)", write("(let ((acc '())) (for-each (lambda (x y) (set! acc (cons x acc))) '(2 1) '(a b c . d)) acc)"));
        // Proper lists work as before (and circular ones, circularListsRaise).
        assertEquals("((2 3 4) () (11 22) ())", write("(list (map (lambda (x) (+ x 1)) '(1 2 3)) (map car '()) (map + '(1 2) '(10 20)) (map + '() '(1)))"));
    }

    /**
     * The error object names the circular list as its irritant: printing it (an uncaught error's
     * message, display, write) terminates, with datum labels where write needs them (design.md D9).
     */
    @Test
    public void circularIrritantsPrint() {
        assertEquals("#0=(1 2 3 . #0#)", write(circular("(guard (e (#t (car (error-object-irritants e)))) (length c))")));
        try (Context context = Context.create("scheme")) {
            PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval("scheme", circular("(length c)")));
            assertTrue(e.getMessage(), e.getMessage().contains("length: not a proper list #0=(1 2 3 . #0#)"));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("scheme").out(out).build()) {
            context.eval("scheme", circular("(display c)"));
        }
        assertTrue(out.toString(StandardCharsets.UTF_8), out.toString(StandardCharsets.UTF_8).startsWith("#0=(1 2 3 . #0#)"));
    }

    /** What expr raises, as its error object's message, or its value. */
    private static String message(String expr) {
        return write("(guard (e ((error-object? e) (error-object-message e))) " + expr + ")");
    }

    private static final String SET_CAR = "\"set-car!: pair is immutable (a literal)\"";
    private static final String SET_CDR = "\"set-cdr!: pair is immutable (a literal)\"";
    private static final String LIST_SET = "\"list-set!: pair is immutable (a literal)\"";

    /**
     * R7RS §3.4: "literal constants ... are immutable ... it is an error to attempt to store a new
     * value into a location that is denoted by an immutable object". Literal pairs are immutable,
     * like literal strings, vectors and bytevectors (design.md D1, D9): mutating one signals an
     * error naming the procedure, however the literal reached the program.
     */
    @Test
    public void literalPairsAreImmutable() {
        assertEquals(SET_CAR, message("(set-car! '(1 2) 9)"));
        assertEquals(SET_CDR, message("(set-cdr! (quote (a . b)) 1)"));
        assertEquals(LIST_SET, message("(list-set! '(1 2) 1 9)"));
        // Every pair of the literal, at any depth, and in a self-evaluating vector (§4.1.2).
        assertEquals(SET_CDR, message("(set-cdr! (cddr '(1 2 3)) 9)"));
        assertEquals(SET_CAR, message("(set-car! (cadr '(1 (2 3))) 9)"));
        assertEquals(SET_CAR, message("(set-car! (vector-ref '#((1) 2) 0) 9)"));
        assertEquals(SET_CAR, message("(set-car! (vector-ref #((1) 2) 0) 9)"));
        // The same literal every time a procedure returns it (R7RS §4.1.2's (g) example).
        assertEquals(SET_CAR, message("(let () (define (g) '(constant-list)) (set-car! (g) 3))"));
        // A circular literal (datum labels, §2.4).
        assertEquals(SET_CDR, message("(set-cdr! '#0=(a . #0#) 1)"));
        // A syntax-rules template's quoted data, constant or with pattern variables inside, is a
        // quote form in the expansion, so a literal; so are case's data.
        assertEquals(SET_CAR, message("(let () (define-syntax m (syntax-rules () ((_) '(1 2)))) (set-car! (m) 9))"));
        assertEquals(SET_CAR, message("(let () (define-syntax m (syntax-rules () ((_ x ...) '(x ... end)))) (set-car! (m a b) 9))"));
        assertEquals(SET_CAR, message("(let ((k 'a)) (case k ((a b) (set-car! (memv k '(a b)) 9))))"));
        // eval's quoted data is a literal of the evaluated program, even when built at run time.
        assertEquals(SET_CAR, message("(set-car! (eval ''(1 2) (environment '(scheme base))) 9)"));
        assertEquals(SET_CAR, message("(set-car! (eval (list 'quote (list 1 2)) (environment '(scheme base))) 9)"));
        // quasiquote (§4.2.8): "Portions that do not need to be rebuilt are always literal".
        assertEquals(SET_CAR, message("(set-car! `(1 2) 9)"));
        assertEquals(SET_CAR, message("(let ((x 0)) (set-car! (cadr `(,x (1 2))) 9))"));
        assertEquals(SET_CAR, message("(let ((x 0)) (set-car! (cdr `(,x 2)) 9))"));
        assertEquals("(9 2)", write("(let* ((x 0) (l `(,x 2))) (set-car! l 9) l)"));
        assertEquals("(9 2)", write("(let* ((x '(2)) (l `(1 ,@x))) (set-car! l 9) l)"));
        // The error is an error object (D8), raised before anything changes.
        assertEquals("((1 2) #t)", write("(let ((l '(1 2))) (list (guard (e (#t l)) (set-car! l 9)) (error-object? (guard (e (#t e)) (set-cdr! l 9)))))"));
    }

    /** Pairs made at run time stay mutable: cons, list, list-copy (even of a literal), read, ... */
    @Test
    public void runTimePairsAreMutable() {
        assertEquals("((9 . 2) (9 2) (9 2) (9 2) (9 2) (9 . 8) (9 2) (9 2) (9 2) (9 1))", write("""
                        (let ((l (list (cons 1 2) (list 1 2) (list-copy '(1 2)) (append '(1) '(2))
                                       (read (open-input-string "(1 2)")) (list-copy '(1 . 2))
                                       (vector->list #(1 2)) (map car '((1) (2))) (reverse '(2 1)) (make-list 2 1))))
                          (for-each (lambda (p) (set-car! p 9)) l)
                          (set-cdr! (list-ref l 5) 8)
                          l)"""));
        // append copies all but its last argument, which it shares: a literal's pairs stay literal.
        assertEquals(SET_CAR, message("(set-car! (cdr (append (list 1) '(2))) 9)"));
        assertEquals("(0 x 2)", write("(let ((l (list 0 1 2))) (list-set! l 1 'x) l)"));
    }

    /** Immutability shows only when mutating: equal?, eqv?, eq? and printing are as for any pair. */
    @Test
    public void literalPairsCompareLikeAnyPairs() {
        assertEquals("(#t #t #t #f #t (1 (2 . 3)))",
                        write("(let ((l '(1 (2 . 3)))) (list (equal? l (list 1 (cons 2 3))) (eq? l l) (eqv? l l) (eqv? l (list-copy l)) (equal? l (list-copy l)) l))"));
    }

    private String run(String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = SchemeMain.newContext(List.of(folder.getRoot().getPath())).out(out).build()) {
            context.eval("scheme", program);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Literals in an included or loaded file, or in a library, are literals too. */
    @Test
    public void literalPairsInFilesAndLibraries() throws IOException {
        Files.writeString(folder.getRoot().toPath().resolve("lit.scm"), "(define lit '(1 2))", StandardCharsets.UTF_8);
        Files.writeString(folder.getRoot().toPath().resolve("lit-lib.sld"),
                        "(define-library (lit-lib) (import (scheme base)) (export lib-lit) (begin (define lib-lit '(1 2))))", StandardCharsets.UTF_8);
        String file = folder.getRoot().toPath().resolve("lit.scm").toString().replace("\\", "\\\\");
        String guard = "(guard (e ((error-object? e) (write (error-object-message e)))) (set-car! %s 9))";
        assertEquals(SET_CAR, run("(import (scheme base) (scheme write)) (include \"" + file + "\") " + guard.formatted("lit")));
        // load defines into the interaction environment (design.md D15).
        assertEquals(SET_CAR, run("(import (scheme base) (scheme write) (scheme load) (scheme eval) (scheme repl)) (load \"" + file + "\") " +
                        guard.formatted("(eval 'lit (interaction-environment))")));
        assertEquals(SET_CAR, run("(import (scheme base) (scheme write) (lit-lib)) " + guard.formatted("lib-lit")));
    }
}
