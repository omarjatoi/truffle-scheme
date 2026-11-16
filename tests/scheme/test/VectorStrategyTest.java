package scheme.test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.junit.Test;

import scheme.runtime.value.SConstant;
import scheme.runtime.value.SVector;

/**
 * M5-06: vector storage strategies. A vector keeps its elements in a {@code long[]} while every one
 * is a fixnum, in a {@code double[]} while every one is a flonum, and otherwise in an
 * {@code Object[]}; the first store that doesn't fit moves it to an {@code Object[]} for good. None
 * of this is visible to Scheme code: these cases check that every procedure sees the same elements
 * whatever the storage, and the Java ones check which storage a vector has.
 */
public class VectorStrategyTest {

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

    // ------------------------------------------------------------------------------------------
    // The storage a vector gets, and its transitions (Java)

    @Test
    public void storageFollowsTheElements() {
        assertTrue(SVector.of(new Object[]{1L, 2L}, false).storage instanceof long[]);
        assertTrue(SVector.of(new Object[]{1.5, -0.0}, false).storage instanceof double[]);
        assertTrue(SVector.of(new Object[]{1L, 2.5}, false).storage instanceof Object[]);
        assertTrue(SVector.of(new Object[]{SConstant.EMPTY_LIST}, false).storage instanceof Object[]);
        assertTrue(SVector.of(new Object[0], false).storage instanceof Object[]);
        assertTrue(SVector.filled(3, 0L).storage instanceof long[]);
        assertTrue(SVector.filled(3, 0.0).storage instanceof double[]);
        assertTrue(SVector.filled(3, Boolean.FALSE).storage instanceof Object[]);
    }

    @Test
    public void aStoreThatFitsKeepsTheStorage() {
        SVector longs = SVector.of(new Object[]{1L, 2L}, false);
        longs.set(0, 7L);
        assertTrue(longs.storage instanceof long[]);
        assertEquals(7L, longs.get(0));
        SVector doubles = SVector.of(new Object[]{1.5, 2.5}, false);
        doubles.set(1, -0.0);
        assertTrue(doubles.storage instanceof double[]);
        assertEquals(-0.0, doubles.get(1));
    }

    @Test
    public void aStoreThatDoesNotFitGeneralizes() {
        Object a = Boolean.TRUE;
        SVector longs = SVector.of(new Object[]{1L, 2L, 3L}, false);
        longs.set(1, a);
        assertTrue(longs.storage instanceof Object[]);
        assertArrayEquals(new Object[]{1L, a, 3L}, longs.toArray());
        // Exactness is kept: a fixnum doesn't go into flonum storage, nor a flonum into fixnum storage.
        SVector doubles = SVector.of(new Object[]{1.5, 2.5}, false);
        doubles.set(0, 1L);
        assertTrue(doubles.storage instanceof Object[]);
        assertArrayEquals(new Object[]{1L, 2.5}, doubles.toArray());
        SVector longs2 = SVector.filled(2, 0L);
        longs2.set(0, 0.5);
        assertTrue(longs2.storage instanceof Object[]);
        assertArrayEquals(new Object[]{0.5, 0L}, longs2.toArray());
        // Never back: an Object[] vector stays one, even when its elements all fit again.
        longs.set(1, 2L);
        assertTrue(longs.storage instanceof Object[]);
    }

    @Test
    public void copiesKeepTheStorageAndShareNothing() {
        SVector doubles = SVector.of(new Object[]{1.5, 2.5, 3.5}, true);
        SVector copy = doubles.copy(1, 3);
        assertTrue(copy.storage instanceof double[]);
        assertTrue(!copy.immutable);
        copy.set(0, SConstant.UNSPECIFIED);
        assertEquals(2.5, doubles.get(1));
        assertSame(SConstant.UNSPECIFIED, copy.get(0));
    }

    // ------------------------------------------------------------------------------------------
    // Transitions, from Scheme

    @Test
    public void mixedStores() {
        assertEquals("#(0 a 0)", write("(let ((v (make-vector 3 0))) (vector-set! v 1 'a) v)"));
        assertEquals("#(0 1.5)", write("(let ((v (make-vector 2 1.5))) (vector-set! v 0 0) v)"));
        assertEquals("#(2.5 2)", write("(let ((v (vector 1 2))) (vector-set! v 0 2.5) v)"));
        assertEquals("#(1267650600228229401496703205376 2)", write("(let ((v (vector 1 2))) (vector-set! v 0 (expt 2 100)) v)"));
        assertEquals("#(1/2 2)", write("(let ((v (vector 1 2))) (vector-set! v 0 1/2) v)"));
        assertEquals("(#t #f #t)", write("(let ((v (vector 1 2.0))) (list (exact? (vector-ref v 0)) (exact? (vector-ref v 1)) (eqv? (vector-ref v 1) 2.0)))"));
        assertEquals("(-0.0 +nan.0 +inf.0)", write("(let ((v (make-vector 3 -0.0))) (vector-set! v 1 (/ 0.0 0.0)) (vector-set! v 2 (/ 1.0 0.0)) (vector->list v))"));
        // One store site, many vectors of different storage: every store lands.
        assertEquals("(#(x 1 2) #(x 1.5 2.5) #(x b c) #(x 9))", write(
                        "(let ((vs (list (vector 0 1 2) (vector 0.5 1.5 2.5) (vector 'a 'b 'c) (vector 0 9)))) (for-each (lambda (v) (vector-set! v 0 'x)) vs) vs)"));
        assertEquals("(#(7 1 2) #(7 1.5 2.5) #(7 b c))", write(
                        "(let ((vs (list (vector 0 1 2) (vector 0.5 1.5 2.5) (vector 'a 'b 'c)))) (for-each (lambda (v) (vector-set! v 0 7)) vs) vs)"));
        // A hot loop that changes a vector's storage halfway through.
        assertEquals("(4950 4950.0 a)", write(
                        "(let ((v (make-vector 100 0))) (do ((i 0 (+ i 1))) ((= i 100)) (vector-set! v i i)) " +
                                        "(let ((s (do ((i 0 (+ i 1)) (s 0 (+ s (vector-ref v i)))) ((= i 100) s)))) " +
                                        "(do ((i 0 (+ i 1))) ((= i 100)) (vector-set! v i (inexact i))) " +
                                        "(vector-set! v 99 'a) " +
                                        "(list s (do ((i 0 (+ i 1)) (s 0.0 (+ s (vector-ref v i)))) ((= i 99) (+ s 99.0))) (vector-ref v 99))))"));
    }

    @Test
    public void indexErrors() {
        assertEquals("error", raises("(vector-ref (vector 1 2) 2)"));
        assertEquals("error", raises("(vector-ref (vector 1.5) -1)"));
        assertEquals("error", raises("(vector-ref (vector 1 2) 1.0)"));
        assertEquals("error", raises("(vector-set! (vector 1 2) 2 0)"));
        assertEquals("error", raises("(vector-set! (vector 1.5) 1 'a)"));
        assertEquals("error", raises("(vector-ref '(1) 0)"));
        assertEquals("error", raises("(vector-set! 'v 0 0)"));
    }

    @Test
    public void literalsAreImmutableWhateverTheStorage() {
        assertEquals("error", raises("(vector-set! '#(1 2) 0 3)"));
        assertEquals("error", raises("(vector-set! '#(1 2) 0 'a)"));
        assertEquals("error", raises("(vector-set! '#(1.5) 0 2.5)"));
        assertEquals("error", raises("(vector-set! '#(a) 0 'b)"));
        assertEquals("error", raises("(vector-fill! '#(1 2) 0)"));
        assertEquals("error", raises("(vector-fill! '#(1.0 2.0) 'x)"));
        assertEquals("error", raises("(vector-copy! '#(1 2) 0 (vector 3))"));
        assertEquals("(#(1 2) #(1.5))", write("(let ((a '#(1 2)) (b '#(1.5))) (guard (e (#t (list a b))) (vector-set! a 0 'x)))"));
        assertEquals("#(9 2)", write("(let ((v (vector-copy '#(1 2)))) (vector-set! v 0 9) v)"));
    }

    // ------------------------------------------------------------------------------------------
    // Every procedure, across storages

    @Test
    public void equalAcrossStorages() {
        assertEquals("#t", write("(equal? (vector 1 2) (let ((v (vector 'a 2))) (vector-set! v 0 1) v))"));
        assertEquals("#t", write("(equal? (vector 1.5 2.5) (let ((v (vector 'a 2.5))) (vector-set! v 0 1.5) v))"));
        assertEquals("#t", write("(equal? '#(1 2) (vector 1 2))"));
        assertEquals("#f", write("(equal? (vector 1.0) (vector 1))"));
        assertEquals("#f", write("(equal? (vector 0.0) (vector -0.0))"));
        assertEquals("#f", write("(equal? (vector 1 2) (vector 1 3))"));
        assertEquals("#f", write("(equal? (vector 1 2) (vector 1 2 3))"));
        assertEquals("#t", write("(equal? (vector (vector 1 2) (vector 1.5)) (vector (vector 1 2) (vector 1.5)))"));
        assertEquals("#f", write("(equal? (vector (vector 1 2)) (vector (vector 1 2.0)))"));
        assertEquals("(#t #t)", write("(let ((v (vector 1 2))) (list (eqv? v v) (eq? (vector-ref v 0) 1)))"));
    }

    @Test
    public void printing() {
        assertEquals("#(1 2 3)", write("(vector 1 2 3)"));
        assertEquals("#(1.5 -0.0 2.0)", write("(vector 1.5 -0.0 2.0)"));
        assertEquals("#(1 2.0 a)", write("(vector 1 2.0 'a)"));
        assertEquals("#0=#(1 #0#)", write("(let ((v (vector 1 2))) (vector-set! v 1 v) v)"));
        assertEquals("#(#(1 2) #(1 2))", write("(let ((v (vector 1 2))) (vector v v))"));
        assertEquals("#0=(#(1 2) . #0#)", write("(let ((p (list (vector 1 2)))) (set-cdr! p p) p)"));
    }

    @Test
    public void copyProcedures() {
        // vector-copy keeps the elements and gives a vector of its own.
        assertEquals("(#(2.5 3.5) #(1.5 2.5 3.5))", write("(let* ((v (vector 1.5 2.5 3.5)) (c (vector-copy v 1))) (vector-set! (vector-copy v) 0 'x) (list c v))"));
        // vector-copy! between every pair of storages.
        assertEquals("#(0 1 2 0)", write("(let ((to (make-vector 4 0))) (vector-copy! to 1 (vector 1 2)) to)"));
        assertEquals("#(0 a b 0)", write("(let ((to (make-vector 4 0))) (vector-copy! to 1 (vector 'a 'b)) to)"));
        assertEquals("#(0 1.5 0)", write("(let ((to (make-vector 3 0))) (vector-copy! to 1 (vector 1.5 2.5) 0 1) to)"));
        assertEquals("#(0.0 1 2)", write("(let ((to (make-vector 3 0.0))) (vector-copy! to 1 (vector 1 2)) to)"));
        assertEquals("#(x 1 2)", write("(let ((to (make-vector 3 'x))) (vector-copy! to 1 (vector 1 2)) to)"));
        assertEquals("#(7 8 3)", write("(let ((to (vector 1 2 3)) (from (vector 'a 7 8))) (vector-set! from 0 6) (vector-copy! to 0 from 1) to)"));
        assertEquals("#(1.5 1.5 2.5)", write("(let ((v (vector 1.5 2.5 3.5))) (vector-copy! v 1 v 0 2) v)"));
        assertEquals("#(2 3 3)", write("(let ((v (vector 1 2 3))) (vector-copy! v 0 v 1) v)"));
        // vector-append, vector-fill!, and the conversions.
        assertEquals("#(1 2 1.5 a)", write("(vector-append (vector 1 2) (vector 1.5) (vector 'a))"));
        assertEquals("(#(1 2 3) #(1.5 2.5))", write("(list (vector-append (vector 1) (vector 2 3)) (vector-append (vector 1.5) '#(2.5)))"));
        assertEquals("#(1 x x 4)", write("(let ((v (vector 1 2 3 4))) (vector-fill! v 'x 1 3) v)"));
        assertEquals("#(1 0 0 4)", write("(let ((v (vector 1 2 3 4))) (vector-fill! v 0 1 3) v)"));
        assertEquals("#(1.0 0 0 4.0)", write("(let ((v (vector 1.0 2.0 3.0 4.0))) (vector-fill! v 0 1 3) v)"));
        assertEquals("((1 2) (2.5) #(1 2) #(1.5 2.5))", write("(list (vector->list (vector 1 2)) (vector->list (vector 1.5 2.5) 1) (list->vector '(1 2)) (list->vector (list 1.5 2.5)))"));
        assertEquals("(#(2 4) #(1.0 4.0))", write("(list (vector-map (lambda (x) (* 2 x)) (vector 1 2)) (vector-map (lambda (x) (* x x)) (vector 1.0 2.0)))"));
        assertEquals("error", raises("(vector->string (vector 1 2))"));
        assertEquals("3", write("(vector-length (vector 1.5 2 'a))"));
    }

    @Test
    public void quasiquoteAndRecords() {
        assertEquals("#(1 2 3)", write("(let ((x 2)) `#(1 ,x 3))"));
        assertEquals("#(1.5 a)", write("(let ((x 'a)) `#(1.5 ,x))"));
        assertEquals("(1 2)", write("(let () (define-record-type point (make-point x y) point? (x point-x) (y point-y)) (let ((p (make-point 1 2))) (list (point-x p) (point-y p))))"));
    }
}
