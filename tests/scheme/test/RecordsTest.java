package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.Test;

import scheme.runtime.PrimitiveRootNode;
import scheme.runtime.value.SClosure;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SRecord;

/**
 * M7-04: records (R7RS §5.5) on Truffle's Static Object Model (design.md D1). Each evaluation of a
 * {@code define-record-type} makes a new type; its records are static objects; its constructor,
 * predicate, accessors and modifiers are primitives made for it, which call sites run in place.
 * Under {@code //tests:unit_torture} these run with a capture and resume at every non-tail call.
 */
public class RecordsTest {

    private static final String SCHEME = "scheme";

    private static final String POINT = "(define-record-type point (make-point x y) point? (x point-x set-point-x!) (y point-y))";

    private static String run(String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            context.eval(SCHEME, program);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void check(String defs, String expected, String expr) {
        String out = run(defs + "\n(write '" + expected + ") (display \"|\") (write " + expr + ")");
        String[] parts = out.split("\\|", 2);
        assertEquals(expr, parts[0], parts[1]);
    }

    /** The raw values of {@code exprs} after {@code defs}, through {@link ResultProbe}. */
    private static List<Object> raw(String defs, String... exprs) {
        List<Object> values = new ArrayList<>();
        try (Context context = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).build()) {
            ResultProbe probe = context.getEngine().getInstruments().get(ResultProbe.ID).lookup(ResultProbe.class);
            context.eval(SCHEME, defs);
            for (String expr : exprs) {
                context.eval(Source.newBuilder(SCHEME, expr, ResultProbe.SOURCE).buildLiteral());
                values.add(probe.last);
            }
        }
        return values;
    }

    @Test
    public void recordsAreStaticObjects() {
        List<Object> values = raw(POINT + "(define-record-type triple (make-triple c a) triple? (a triple-a) (b triple-b) (c triple-c))",
                        "(make-point 1 2)", "(make-triple 3 1)", "point-x", "make-point");
        SRecord p = (SRecord) values.get(0);
        assertEquals("point", p.type.name);
        assertEquals(1L, p.get(0));
        assertEquals(2L, p.get(1));
        // A class the Static Object Model made, not SRecord with an array of fields.
        assertNotEquals(SRecord.class, p.getClass());
        // The constructor's arguments go to their fields; a field it has no argument for is
        // unspecified.
        SRecord t = (SRecord) values.get(1);
        assertEquals(List.of(1L, SConstant.UNSPECIFIED, 3L), List.of(t.get(0), t.get(1), t.get(2)));
        // Record procedures are primitives of their own, named by their bindings.
        for (Object procedure : values.subList(2, 4)) {
            assertTrue(((SClosure) procedure).callTarget.getRootNode() instanceof PrimitiveRootNode);
        }
        assertEquals("point-x", ((SClosure) values.get(2)).getName());
        check(POINT, "(\"#<procedure point-x>\" \"#<procedure make-point>\" \"#<procedure point?>\" \"#<procedure set-point-x!>\")",
                        "(map (lambda (p) (let ((o (open-output-string))) (write p o) (get-output-string o))) (list point-x make-point point? set-point-x!))");
    }

    @Test
    public void eachEvaluationMakesANewType() {
        String defs = "(define (make-type) (define-record-type cell (make-cell v) cell? (v cell-v set-cell-v!)) (list make-cell cell? cell-v set-cell-v!))" +
                        "(define a (make-type)) (define b (make-type))" +
                        "(define (ctor t) (car t)) (define (pred t) (cadr t)) (define (get t) (caddr t)) (define (put t) (cadddr t))";
        check(defs, "(#t #f #f #t)", "(let ((x ((ctor a) 1)) (y ((ctor b) 2))) (list ((pred a) x) ((pred a) y) ((pred b) x) ((pred b) y)))");
        check(defs, "(1 wrong wrong 2)",
                        "(let ((x ((ctor a) 1)) (y ((ctor b) 2)))" +
                                        " (list ((get a) x) (guard (e ((error-object? e) 'wrong)) ((get a) y)) (guard (e ((error-object? e) 'wrong)) ((put b) x 0)) ((get b) y)))");
        // Many types made at one call site, each with its own procedures, the site past its cache;
        // types with as many fields share a shape, and stay distinct.
        check("", "(1999000 #t)", """
                        (let loop ((i 0) (sum 0) (ok #t) (prev-pred #f) (prev #f))
                          (if (= i 2000)
                              (list sum ok)
                              (let ()
                                (define-record-type r (make-r a b) r? (a r-a) (b r-b set-r-b!))
                                (let ((x (make-r i 0)))
                                  (set-r-b! x i)
                                  (loop (+ i 1) (+ sum (r-a x) (- (r-b x) i))
                                        (and ok (r? x) (not (and prev-pred (prev-pred x))) (not (r? prev)))
                                        r? x)))))""");
    }

    @Test
    public void wrongTypesAndAritiesAreErrors() {
        String defs = POINT + "(define-record-type other (make-other x) other? (x other-x))";
        String message = "(lambda (thunk) (guard (e ((error-object? e) (error-object-message e))) (thunk)))";
        check(defs, "(\"point-x: not a record of type point: #<record other>\" \"set-point-x!: not a record of type point: 5\")",
                        "(let ((m " + message + ")) (list (m (lambda () (point-x (make-other 1)))) (m (lambda () (set-point-x! 5 0)))))");
        check(defs, "(\"make-point: expected 2 arguments, got 1\" \"point-x: expected 1 arguments, got 0\")",
                        "(let ((m " + message + ")) (list (m (lambda () (make-point 1))) (m (lambda () (point-x)))))");
        // Records are not vectors, pairs or procedures (design.md D1), and only their own type's.
        check(defs, "(#f #f #f #f #f #t)", "(let ((p (make-point 1 2))) (list (vector? p) (pair? p) (procedure? p) (other? p) (point? (vector 1 2)) (point? p)))");
        // A field the constructor names must be a field.
        check("", "\"define-record-type: z is not a field of point\"",
                        "(guard (e ((error-object? e) (error-object-message e))) (let () (define-record-type point (make-point z) point? (x point-x)) 'defined))");
    }

    @Test
    public void recordProceduresAsValues() {
        // Through apply, map, a variable, and at sites in tail position and not.
        check(POINT, "(1 (1 2 3) 8 #t 42)", """
                        (let* ((p (make-point 1 2)) (get point-x) (put! set-point-x!)
                               (tail (lambda (q) (point-y q))))
                          (list (apply point-x (list p))
                                (map point-x (list (make-point 1 0) (make-point 2 0) (make-point 3 0)))
                                (tail (make-point 7 8))
                                (point? (apply make-point '(7 8)))
                                (begin (put! p 42) (get p))))""");
    }

    @Test
    public void recordsInReenteredContinuations() {
        // A record is shared state, not part of a frame: re-entering a continuation sees its
        // fields as they are, and records made after the capture are new each time.
        check(POINT, "((3 4) (#f #f #f))", """
                        (let* ((k #f) (r (make-point 0 0)) (made '())
                               (n (call/cc (lambda (c) (set! k c) 0))))
                          (set-point-x! r (+ (point-x r) 1))
                          (set! made (cons (make-point n n) made))
                          (if (< n 3)
                              (k (+ n 1))
                              (list (list n (point-x r))
                                    (list (eq? (car made) (cadr made)) (eq? (cadr made) (caddr made)) (eq? (car made) (cadddr made))))))""");
    }

    @Test
    public void interopSeesFieldsAsTheyAre() {
        try (Context context = Context.create(SCHEME)) {
            Value p = context.eval(SCHEME, POINT + "(define p (make-point 1 2)) (set-point-x! p 10) p");
            assertEquals(10, p.getMember("x").asInt());
            assertEquals(2, p.getMember("y").asInt());
            assertEquals("point", p.getMetaObject().getMetaSimpleName());
            Value none = context.eval(SCHEME, "(define-record-type unit (make-unit) unit?) (make-unit)");
            assertTrue(none.hasMembers());
            assertEquals(0, none.getMemberKeys().size());
            // Two types with the same fields: the same shape, each its own meta object.
            Value q = context.eval(SCHEME, "(define-record-type point2 (make-point2 x y) point2? (x point2-x) (y point2-y)) (make-point2 3 4)");
            assertEquals(3, q.getMember("x").asInt());
            assertTrue(q.getMetaObject().isMetaInstance(q));
            assertFalse(q.getMetaObject().isMetaInstance(p));
            assertNull(q.getMember("z"));
        }
    }
}
