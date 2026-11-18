package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.TypeLiteral;
import org.graalvm.polyglot.Value;
import org.junit.Test;

import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.interop.ExceptionType;
import com.oracle.truffle.api.interop.InteropLibrary;

import scheme.compiler.ProgramCompiler;
import scheme.runtime.GlobalCell;
import scheme.runtime.SchemeContext;
import scheme.runtime.SchemeLanguage;

/**
 * M6-02: interop for embedders (design.md D10). Scheme values answer {@code InteropLibrary} as what
 * they are, Java values become Scheme values (and anything else stays a foreign object Scheme can
 * only pass around), procedures are executables that run under a root prompt of their own (D6), and
 * the interaction environment is the language's top scope ({@code getBindings}).
 */
public class EmbeddingTest {

    private static final String SCHEME = "scheme";

    private static Context context() {
        return Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).build();
    }

    private static Value eval(Context context, String text) {
        return context.eval(SCHEME, text);
    }

    private static List<Object> elements(Value iterable) {
        List<Object> items = new ArrayList<>();
        Value it = iterable.getIterator();
        while (it.hasIteratorNextElement()) {
            Value next = it.getIteratorNextElement();
            items.add(next.isNumber() ? (Object) next.asLong() : next.toString());
        }
        return items;
    }

    // ---------------------------------------------------------------------------------------------
    // Scheme values in Java

    @Test
    public void numbers() {
        try (Context context = context()) {
            Value fixnum = eval(context, "42");
            assertTrue(fixnum.isNumber());
            assertTrue(fixnum.fitsInInt());
            assertEquals(42L, fixnum.asLong());
            Value flonum = eval(context, "2.5");
            assertTrue(flonum.fitsInDouble());
            assertEquals(2.5, flonum.asDouble(), 0);
            // Bignums are big integers: never a long (they would have been demoted, D1), a double only exactly.
            Value big = eval(context, "(expt 2 100)");
            assertTrue(big.isNumber());
            assertFalse(big.fitsInLong());
            assertTrue(big.fitsInBigInteger());
            assertEquals(BigInteger.TWO.pow(100), big.asBigInteger());
            assertTrue(big.fitsInDouble());
            assertEquals(Math.pow(2, 100), big.asDouble(), 0);
            Value odd = eval(context, "(+ (expt 2 100) 1)");
            assertFalse(odd.fitsInDouble());
            assertThrows(ClassCastException.class, odd::asDouble);
            assertEquals("-1267650600228229401496703205377", eval(context, "(- -1 (expt 2 100))").toString());
            // Exact rationals are numbers that fit in a double only when it is exact.
            Value half = eval(context, "1/2");
            assertTrue(half.isNumber());
            assertTrue(half.fitsInDouble());
            assertFalse(half.fitsInLong());
            assertFalse(half.fitsInBigInteger());
            assertEquals(0.5, half.asDouble(), 0);
            Value third = eval(context, "1/3");
            assertTrue(third.isNumber());
            assertFalse(third.fitsInDouble());
            assertFalse(third.fitsInFloat());
            assertEquals("1/3", third.toString());
            // Non-real complex numbers are not interop numbers (interop's are real), but are Scheme numbers.
            Value complex = eval(context, "(make-rectangular 1 2)");
            assertFalse(complex.isNumber());
            assertEquals("1+2i", complex.toString());
            assertEquals("number", complex.getMetaObject().getMetaSimpleName());
            assertEquals("+inf.0", eval(context, "(/ 1.0 0.)").toString());
            assertEquals("-0.0", eval(context, "-0.0").toString());
        }
    }

    @Test
    public void stringsCharactersSymbolsBooleans() {
        try (Context context = context()) {
            Value s = eval(context, "(string-append \"ab\" \"c\")");
            assertTrue(s.isString());
            assertEquals("abc", s.asString());
            assertEquals("\"abc\"", s.toString());
            assertEquals("λ→😀", eval(context, "(string #\\λ #\\x2192 #\\x1F600)").asString());
            // A character is a one-character string.
            Value c = eval(context, "#\\λ");
            assertTrue(c.isString());
            assertEquals("λ", c.asString());
            assertEquals("#\\λ", c.toString());
            assertEquals("😀", eval(context, "#\\x1F600").asString());
            assertEquals("#\\space", eval(context, "#\\space").toString());
            // A symbol is not a string: its name is its display string.
            Value sym = eval(context, "'hello");
            assertFalse(sym.isString());
            assertEquals("hello", sym.toString());
            assertEquals("|a b|", eval(context, "(string->symbol \"a b\")").toString());
            assertEquals("symbol", sym.getMetaObject().getMetaSimpleName());
            Value t = eval(context, "(< 1 2)");
            assertTrue(t.isBoolean());
            assertTrue(t.asBoolean());
            assertEquals("#t", t.toString());
            assertEquals("#f", eval(context, "(> 1 2)").toString());
        }
    }

    @Test
    public void nullsAndConstants() {
        try (Context context = context()) {
            Value empty = eval(context, "'()");
            assertTrue(empty.isNull());
            assertEquals("()", empty.toString());
            // The empty list is a list, so it iterates too: no elements.
            assertTrue(empty.hasIterator());
            assertEquals(List.of(), elements(empty));
            Value unspecified = eval(context, "(if #f #f)");
            assertTrue(unspecified.isNull());
            assertFalse(unspecified.hasIterator());
            Value eof = eval(context, "(eof-object)");
            assertFalse(eof.isNull());
            assertEquals("eof-object", eof.getMetaObject().getMetaSimpleName());
        }
    }

    @Test
    public void listsAreIterators() {
        try (Context context = context()) {
            Value list = eval(context, "(list 1 2 3)");
            assertTrue(list.hasIterator());
            assertFalse(list.hasArrayElements());
            assertEquals(List.of(1L, 2L, 3L), elements(list));
            // An improper list's elements stop at the last pair.
            assertEquals(List.of(1L, 2L), elements(eval(context, "'(1 2 . 3)")));
            assertEquals("(1 2 . 3)", eval(context, "'(1 2 . 3)").toString());
            assertEquals(List.of("\"a\"", "(b)"), elements(eval(context, "(list \"a\" '(b))")));
            // Each iterator walks the list as it is when it gets there.
            Value pairs = eval(context, "(define mutable (list 1 2 3)) mutable");
            Value it = pairs.getIterator();
            assertEquals(1, it.getIteratorNextElement().asInt());
            eval(context, "(set-car! (cdr mutable) 20)");
            assertEquals(20, it.getIteratorNextElement().asInt());
        }
    }

    /**
     * A pair that doesn't start a proper list (an improper list's, a circular list's) is also an
     * object whose members are car and cdr, so Java sees an improper list's last cdr, which the
     * iterator leaves out; both are writable, as set-car! and set-cdr! write them, except a literal's
     * (read-only, as literal vectors are). A proper list has
     * no members, so a Java Object is an Iterable of its elements, as before.
     */
    @Test
    public void pairsOfImproperListsHaveCarAndCdrMembers() {
        try (Context context = context()) {
            Value improper = eval(context, "'(1 2 . 3)");
            assertTrue(improper.hasIterator());
            assertTrue(improper.hasMembers());
            assertEquals(Set.of("car", "cdr"), improper.getMemberKeys());
            assertEquals(1, improper.getMember("car").asInt());
            Value second = improper.getMember("cdr");
            assertEquals(2, second.getMember("car").asInt());
            assertEquals(3, second.getMember("cdr").asInt());
            assertFalse(improper.hasMember("cadr"));
            assertNull(improper.getMember("cadr"));
            assertFalse(second.getMember("cdr").hasMembers());
            assertTrue(eval(context, "(cons 1 2)").hasMembers());
            // Writable, and Scheme sees the writes.
            Value pair = eval(context, "(define p (cons 1 (cons 2 3))) p");
            pair.putMember("car", "one");
            pair.getMember("cdr").putMember("cdr", 30);
            assertEquals("(\"one\" 2 . 30)", eval(context, "p").toString());
            assertThrows(UnsupportedOperationException.class, () -> pair.putMember("cadr", 1));
            // A literal's pairs are constants (R7RS §3.4, design.md D1): their members are read-only.
            Value literal = eval(context, "(define lit '(1 2 . 3)) lit");
            assertEquals(1, literal.getMember("car").asInt());
            assertThrows(UnsupportedOperationException.class, () -> literal.putMember("car", 9));
            assertThrows(UnsupportedOperationException.class, () -> literal.getMember("cdr").putMember("cdr", 9));
            assertEquals("(1 2 . 3)", eval(context, "lit").toString());
            // A circular list isn't proper: members, and the iterator walks it as before.
            Value circular = eval(context, "(define c (list 1 2)) (set-cdr! (cdr c) c) c");
            assertTrue(circular.hasMembers());
            assertEquals(2, circular.getMember("cdr").getMember("car").asInt());
            Value it = circular.getIterator();
            assertEquals(1, it.getIteratorNextElement().asInt());
            assertEquals(2, it.getIteratorNextElement().asInt());
            assertEquals(1, it.getIteratorNextElement().asInt());
            // An improper list as a Java Object is a Map of its members.
            assertTrue(improper.as(Object.class) instanceof Map<?, ?> map && map.keySet().equals(Set.of("car", "cdr")));
        }
    }

    @Test
    public void properListsHaveNoMembers() {
        try (Context context = context()) {
            Value list = eval(context, "(define l (list 1 2)) l");
            assertTrue(list.hasIterator());
            assertFalse(list.hasMembers());
            assertFalse(list.hasMember("car"));
            assertThrows(UnsupportedOperationException.class, () -> list.getMember("car"));
            assertThrows(UnsupportedOperationException.class, () -> list.putMember("car", 0));
            assertFalse(eval(context, "'()").hasMembers());
            // As a Java Object (a host method's Object parameter), an Iterable of its elements.
            Object converted = list.as(Object.class);
            assertTrue(converted.getClass().getName(), converted instanceof Iterable<?> && !(converted instanceof Map<?, ?>));
            List<Object> items = new ArrayList<>();
            ((Iterable<?>) converted).forEach(items::add);
            assertEquals("[1, 2]", items.toString());
            List<Long> longs = new ArrayList<>();
            for (Long item : list.as(new TypeLiteral<Iterable<Long>>() {
            })) {
                longs.add(item);
            }
            assertEquals(List.of(1L, 2L), longs);
            // set-cdr! can make it improper: the answer follows.
            eval(context, "(set-cdr! (cdr l) 3)");
            assertTrue(list.hasMembers());
        }
    }

    @Test
    public void vectorsAndBytevectorsAreArrays() {
        try (Context context = context()) {
            Value v = eval(context, "(vector 1 \"two\" 3.0)");
            assertTrue(v.hasArrayElements());
            assertEquals(3, v.getArraySize());
            assertEquals("two", v.getArrayElement(1).asString());
            v.setArrayElement(0, 42);
            assertEquals("#(42 \"two\" 3.0)", v.toString());
            assertThrows(ArrayIndexOutOfBoundsException.class, () -> v.getArrayElement(3));
            assertThrows(ArrayIndexOutOfBoundsException.class, () -> v.setArrayElement(-1, 0));
            // Specialized storage (M5-06) generalizes on a store that doesn't fit, as from Scheme.
            Value fixnums = eval(context, "(define fx (make-vector 3 0)) fx");
            fixnums.setArrayElement(1, 7);
            assertEquals("#(0 7 0)", fixnums.toString());
            fixnums.setArrayElement(2, "x");
            assertEquals("#(0 7 \"x\")", fixnums.toString());
            assertTrue(eval(context, "(string? (vector-ref fx 2))").asBoolean());
            Value flonums = eval(context, "(make-vector 2 1.5)");
            flonums.setArrayElement(0, 2.5);
            flonums.setArrayElement(1, 3);
            assertEquals("#(2.5 3)", flonums.toString());
            // Literal vectors are constants (R7RS §3.4): read-only.
            Value literal = eval(context, "'#(1 2)");
            assertThrows(UnsupportedOperationException.class, () -> literal.setArrayElement(0, 5));
            List<Object> asList = literal.as(new TypeLiteral<List<Object>>() {
            });
            assertEquals("[1, 2]", new ArrayList<>(asList).toString());
            // Bytevectors are arrays of exact integers 0-255.
            Value bv = eval(context, "(bytevector 1 255)");
            assertTrue(bv.hasArrayElements());
            assertEquals(255, bv.getArrayElement(1).asInt());
            bv.setArrayElement(0, 128);
            assertEquals("#u8(128 255)", bv.toString());
            assertThrows(ClassCastException.class, () -> bv.setArrayElement(0, 256));
            assertThrows(ClassCastException.class, () -> bv.setArrayElement(0, "x"));
            assertThrows(UnsupportedOperationException.class, () -> eval(context, "#u8(1)").setArrayElement(0, 2));
            assertEquals("bytevector", bv.getMetaObject().getMetaSimpleName());
            // Zero or several values are a read-only array.
            Value mv = eval(context, "(values 1 2)");
            assertEquals(2, mv.getArraySize());
            assertEquals(2, mv.getArrayElement(1).asInt());
            assertEquals(0, eval(context, "(values)").getArraySize());
        }
    }

    @Test
    public void recordsAreObjects() {
        try (Context context = context()) {
            Value point = eval(context, "(define-record-type point (make-point x y) point? (x point-x) (y point-y set-point-y!)) (make-point 3 4)");
            assertTrue(point.hasMembers());
            assertEquals(3, point.getMember("x").asInt());
            assertEquals(4, point.getMember("y").asInt());
            assertEquals(List.of("x", "y"), new ArrayList<>(point.getMemberKeys()));
            assertNull(point.getMember("z"));
            assertThrows(UnsupportedOperationException.class, () -> point.putMember("x", 1));
            // Its meta object is its record type.
            Value type = point.getMetaObject();
            assertTrue(type.isMetaObject());
            assertEquals("point", type.getMetaSimpleName());
            assertTrue(type.isMetaInstance(point));
            assertFalse(type.isMetaInstance(eval(context, "(define-record-type other (make-other) other?) (make-other)")));
            assertFalse(type.isMetaInstance(42));
            assertEquals("record-type", type.getMetaObject().getMetaSimpleName());
        }
    }

    @Test
    public void metaObjectsAreSchemeTypes() {
        try (Context context = context()) {
            String[][] cases = {
                            {"42", "number"}, {"2.5", "number"}, {"(expt 2 100)", "number"}, {"1/3", "number"},
                            {"#t", "boolean"}, {"#\\a", "char"}, {"\"s\"", "string"}, {"'s", "symbol"},
                            {"'(1)", "pair"}, {"'()", "null"}, {"(vector)", "vector"}, {"(bytevector)", "bytevector"},
                            {"car", "procedure"}, {"(lambda () 1)", "procedure"}, {"(current-output-port)", "port"},
                            {"(eof-object)", "eof-object"}, {"(if #f #f)", "unspecified"},
                            {"(guard (e (#t e)) (car 1))", "error-object"}, {"(interaction-environment)", "environment"},
                            {"(values 1 2)", "values"},
            };
            for (String[] c : cases) {
                Value value = eval(context, c[0]);
                assertTrue(c[0], value.getMetaObject() != null);
                Value meta = value.getMetaObject();
                assertTrue(c[0], meta.isMetaObject());
                assertEquals(c[0], c[1], meta.getMetaSimpleName());
                assertEquals(c[0], c[1], meta.getMetaQualifiedName());
                assertTrue(c[0], meta.isMetaInstance(value));
            }
            Value number = eval(context, "42").getMetaObject();
            assertTrue(number.isMetaInstance(7));
            assertTrue(number.isMetaInstance(eval(context, "(make-rectangular 1 2)")));
            assertFalse(number.isMetaInstance("7"));
            assertFalse(number.isMetaInstance(new Object()));
            Value pair = eval(context, "'(1)").getMetaObject();
            assertFalse(pair.isMetaInstance(eval(context, "'()")));
            assertEquals("pair", pair.toString());
        }
    }

    @Test
    public void toStringIsWriteSyntax() {
        try (Context context = context()) {
            assertEquals("(1 \"a\" #\\b c 2.5 #t ())", eval(context, "(list 1 \"a\" #\\b 'c 2.5 #t '())").toString());
            assertEquals("#(1 #(2) \"x\\ny\")", eval(context, "(vector 1 (vector 2) \"x\\ny\")").toString());
            assertEquals("#0=(1 . #0#)", eval(context, "(let ((l (list 1))) (set-cdr! l l) l)").toString());
            assertEquals("#<procedure add>", eval(context, "(define (add a b) (+ a b)) add").toString());
            assertEquals("#<record point>", eval(context, "(define-record-type point (make-point x) point? (x point-x)) (make-point 1)").toString());
            assertEquals("#<values 1 2>", eval(context, "(values 1 2)").toString());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Java values in Scheme

    private static final String KIND = """
                    (lambda (x)
                      (cond ((boolean? x) 'boolean)
                            ((exact-integer? x) 'exact-integer)
                            ((and (real? x) (inexact? x)) 'flonum)
                            ((char? x) 'char)
                            ((string? x) 'string)
                            ((symbol? x) 'symbol)
                            ((null? x) 'null)
                            ((pair? x) 'pair)
                            ((vector? x) 'vector)
                            ((procedure? x) 'procedure)
                            (else 'other)))
                    """;

    @Test
    public void javaValuesBecomeSchemeValues() {
        try (Context context = context()) {
            Value kind = eval(context, KIND);
            assertEquals("exact-integer", kind.execute(42).toString());
            assertEquals("exact-integer", kind.execute(42L).toString());
            assertEquals("exact-integer", kind.execute((byte) 4).toString());
            assertEquals("exact-integer", kind.execute((short) 4).toString());
            assertEquals("flonum", kind.execute(2.5).toString());
            assertEquals("flonum", kind.execute(2.5f).toString());
            assertEquals("boolean", kind.execute(true).toString());
            assertEquals("char", kind.execute('x').toString());
            assertEquals("string", kind.execute("hi").toString());
            // A Java BigInteger is an exact integer, demoted to a fixnum if it fits (D1).
            assertEquals("exact-integer", kind.execute(BigInteger.TEN.pow(30)).toString());
            Value same = eval(context, "(lambda (a b) (list (eqv? a b) (= a b)))");
            assertEquals("(#t #t)", same.execute(BigInteger.valueOf(5), 5).toString());
            assertEquals("(#t #t)", same.execute(BigInteger.TEN.pow(30), eval(context, "(expt 10 30)")).toString());
            // Java values arrive as fresh Scheme values: a string is a new mutable string.
            Value upcaseFirst = eval(context, "(lambda (s) (string-set! s 0 #\\H) s)");
            assertEquals("Hi", upcaseFirst.execute("hi").asString());
            assertEquals("(#t #f)", eval(context, "(lambda (i d) (list (exact? i) (exact? d)))").execute(3, 3.0).toString());
            assertEquals(6, eval(context, "+").execute(1, 2L, (short) 3).asInt());
            assertEquals(3.5, eval(context, "+").execute(1, 2.5).asDouble(), 0);
            // Scheme values handed back to Scheme are the same objects.
            Value vector = eval(context, "(vector 1 2)");
            assertEquals("vector", kind.execute(vector).toString());
            assertTrue(eval(context, "(lambda (a b) (eq? a b))").execute(vector, vector).asBoolean());
            Value proc = eval(context, "car");
            assertEquals("procedure", kind.execute(proc).toString());
            assertEquals("exact-integer", kind.execute(eval(context, "(expt 2 100)")).toString());
        }
    }

    /** A host object for the foreign-object tests. */
    public static final class Thing {
    }

    @Test
    public void foreignValuesAreOpaque() {
        try (Context context = context()) {
            Thing thing = new Thing();
            Value kind = eval(context, KIND);
            assertEquals("other", kind.execute(thing).toString());
            assertEquals("other", kind.execute(new int[]{1, 2}).toString());
            assertEquals("other", kind.execute(List.of(1, 2)).toString());
            // Scheme can store and return it, and compare it by identity.
            Value identity = eval(context, "(lambda (x) (car (list x)))");
            assertSame(thing, identity.execute(thing).asHostObject());
            Value same = eval(context, "(lambda (a b) (list (eq? a b) (eqv? a b) (equal? a b)))");
            assertEquals("(#t #t #t)", same.execute(thing, thing).toString());
            assertEquals("(#f #f #f)", same.execute(thing, new Thing()).toString());
            // Each time it comes in it is the same Scheme object, so eq? holds across calls too.
            Value keep = eval(context, "(define kept #f) (lambda (x) (if kept (eq? x kept) (begin (set! kept x) 'kept)))");
            assertEquals("kept", keep.execute(thing).toString());
            assertTrue(keep.execute(thing).asBoolean());
            assertFalse(keep.execute(new Thing()).asBoolean());
            assertEquals("(#t #t #t)", same.execute(null, null).toString());
            // Printing it shows the foreign object's own display string.
            Value show = eval(context, "(lambda (x) (let ((p (open-output-string))) (write (list x) p) (get-output-string p)))");
            // (Without host access, the host shows a Java object as its class.)
            String shown = show.execute(thing).asString();
            assertTrue(shown, shown.startsWith("(#<foreign ") && shown.endsWith("Thing>)"));
            // Java null stays a foreign null: not the empty list, and null again in Java.
            assertEquals("other", kind.execute((Object) null).toString());
            assertTrue(identity.execute((Object) null).isNull());
            // Primitives given one raise a Scheme error (D8), which guard catches.
            Value attempt = eval(context, """
                            (lambda (x)
                              (map (lambda (f)
                                     (guard (e ((error-object? e) 'error))
                                       (f x)))
                                   (list car
                                         (lambda (x) (+ x 1))
                                         (lambda (x) (vector-ref x 0))
                                         (lambda (x) (string-length x))
                                         (lambda (x) (symbol->string x))
                                         (lambda (x) (length x))
                                         (lambda (x) (x))
                                         (lambda (x) (apply x '()))
                                         (lambda (x) (char-upcase x))
                                         (lambda (x) (bytevector-u8-ref x 0))
                                         (lambda (x) (exact x)))))
                            """);
            assertEquals("(error error error error error error error error error error error)", attempt.execute(thing).toString());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Executing procedures

    @Test
    public void proceduresAreExecutable() {
        try (Context context = context()) {
            Value square = eval(context, "(lambda (x) (* x x))");
            assertTrue(square.canExecute());
            assertEquals(49, square.execute(7).asInt());
            assertEquals(6.25, square.execute(2.5).asDouble(), 0);
            assertEquals(BigInteger.valueOf(Long.MAX_VALUE).pow(2), square.execute(Long.MAX_VALUE).asBigInteger());
            // Named procedures have an executable name and a source location.
            Value add = eval(context, "(define (add a b) (+ a b)) add");
            assertEquals(5, add.execute(2, 3).asInt());
            assertEquals(1, add.getSourceLocation().getStartLine());
            // Primitives.
            assertEquals(3, eval(context, "car").execute(eval(context, "'(3 4)")).asInt());
            assertEquals("#(1 \"a\")", eval(context, "vector").execute(1, "a").toString());
            assertEquals("abc", eval(context, "string-append").execute("a", "b", "c").asString());
            // Variadic procedures and case-lambda.
            assertEquals("(1 2 3)", eval(context, "(lambda args args)").execute(1, 2, 3).toString());
            assertEquals("()", eval(context, "(lambda args args)").execute().toString());
            assertEquals("(1 (2 3))", eval(context, "(lambda (a . rest) (list a rest))").execute(1, 2, 3).toString());
            Value cl = eval(context, "(case-lambda ((a) (list 'one a)) ((a b) (list 'two a b)) ((a . rest) (list 'many a rest)))");
            assertEquals("(one 1)", cl.execute(1).toString());
            assertEquals("(two 1 2)", cl.execute(1, 2).toString());
            assertEquals("(many 1 (2 3))", cl.execute(1, 2, 3).toString());
            // A procedure returned by a procedure, executed in turn; tail calls and apply inside.
            Value adder = eval(context, "(lambda (n) (lambda (m) (+ n m)))");
            assertEquals(10, adder.execute(3).execute(7).asInt());
            assertEquals(6, eval(context, "(lambda (f . args) (apply f args))").execute(eval(context, "+"), 1, 2, 3).asInt());
            Value loop = eval(context, "(define (count n acc) (if (= n 0) acc (count (- n 1) (+ acc 1)))) count");
            assertEquals(1000000, loop.execute(1000000, 0).asInt());
            // Deep non-tail recursion spills under the call's own prompt.
            Value deep = eval(context, "(define (down n) (if (= n 0) 0 (+ 1 (down (- n 1))))) down");
            assertEquals(100000, deep.execute(100000).asInt());
            // Zero values, and several.
            assertEquals(0, eval(context, "(lambda () (values))").execute().getArraySize());
            assertEquals("#<values 1 2>", eval(context, "(lambda () (values 1 2))").execute().toString());
        }
    }

    @Test
    public void wrongArityIsASchemeError() {
        try (Context context = context()) {
            for (String procedure : new String[]{"(lambda (x) x)", "car", "(lambda (a b . c) a)"}) {
                Value p = eval(context, procedure);
                PolyglotException e = assertThrows(PolyglotException.class, p::execute);
                assertTrue(procedure, e.isGuestException());
                assertFalse(procedure, e.isInternalError());
            }
            PolyglotException e = assertThrows(PolyglotException.class, () -> eval(context, "car").execute(1, 2));
            assertTrue(e.isGuestException());
            // The context goes on working.
            assertEquals(1, eval(context, "car").execute(eval(context, "'(1)")).asInt());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Continuations

    @Test
    public void continuationsAreReenteredWithinACall() {
        try (Context context = context()) {
            Value f = eval(context, """
                            (lambda (n)
                              (let ((k #f) (count 0))
                                (call/cc (lambda (c) (set! k c)))
                                (set! count (+ count 1))
                                (if (< count n) (k #f) count)))
                            """);
            assertEquals(5, f.execute(5).asInt());
            assertEquals(3, f.execute(3).asInt());
            // Escapes.
            Value search = eval(context, "(lambda (l) (call/cc (lambda (return) (for-each (lambda (x) (if (negative? x) (return x))) l) #f)))");
            assertEquals(-2, search.execute(eval(context, "'(1 -2 3)")).asInt());
            assertFalse(search.execute(eval(context, "'(1 2)")).asBoolean());
        }
    }

    /**
     * A continuation captured in one call is delimited by that call's prompt (D6): invoking it in a
     * later call (from Java or from Scheme) runs the rest of the earlier call, whose result the later
     * call returns. Any number of times, and without disturbing anything else.
     */
    @Test
    public void continuationsAreReenteredAcrossCalls() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            Value f = eval(context, """
                            (define saved #f)
                            (define entries 0)
                            (define (f)
                              (set! entries (+ entries 1))
                              (let ((n entries))
                                (list 'result
                                      (+ 1 (call/cc (lambda (k) (set! saved k) 1)))
                                      n)))
                            f
                            """);
            assertEquals("(result 2 1)", f.execute().toString());
            Value k = context.getBindings(SCHEME).getMember("saved");
            assertTrue(k.canExecute());
            // From Java: the rest of the first call, with 10 for the call/cc.
            assertEquals("(result 11 1)", k.execute(10).toString());
            // Multi-shot.
            assertEquals("(result 21 1)", k.execute(20).toString());
            // From Scheme, inside another call (and so inside another call's prompt).
            Value resume = eval(context, "(lambda (v) (list 'ignored (saved v)))");
            assertEquals("(result 31 1)", resume.execute(30).toString());
            // A fresh call is unaffected, and captures afresh; the old continuation still works.
            assertEquals("(result 2 2)", f.execute().toString());
            Value k2 = context.getBindings(SCHEME).getMember("saved");
            assertEquals("(result 41 2)", k2.execute(40).toString());
            assertEquals("(result 51 1)", k.execute(50).toString());
            // Re-entry runs dynamic-wind's before thunk again, and leaves by its after thunk.
            Value wind = eval(context, """
                            (lambda ()
                              (dynamic-wind
                                (lambda () (display "[in]"))
                                (lambda () (call/cc (lambda (k) (set! saved k) 0)))
                                (lambda () (display "[out]"))))
                            """);
            assertEquals(0, wind.execute().asInt());
            assertEquals(7, context.getBindings(SCHEME).getMember("saved").execute(7).asInt());
            eval(context, "(flush-output-port)");
            assertEquals("[in][out][in][out]", out.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    public void generatorAcrossCalls() {
        try (Context context = context()) {
            // A generator whose state is a continuation, driven from Java one call at a time: each
            // call resumes the loop captured in the call before it.
            Value next = eval(context, """
                            (define (make-generator lst)
                              (define return #f)
                              (define resume #f)
                              (define (start)
                                (for-each (lambda (x) (call/cc (lambda (k) (set! resume k) (return x)))) lst)
                                (return 'done))
                              (lambda ()
                                (call/cc (lambda (r)
                                  (set! return r)
                                  (if resume (resume #f) (start))))))
                            (make-generator '(a b c))
                            """);
            assertEquals("a", next.execute().toString());
            assertEquals("b", next.execute().toString());
            assertEquals("c", next.execute().toString());
            assertEquals("done", next.execute().toString());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Errors and exit

    @Test
    public void raisedObjectsSurfaceAsGuestErrors() {
        try (Context context = context()) {
            String[][] cases = {
                            {"(lambda () (raise 'boom))", "boom"},
                            {"(lambda () (raise 42))", "42"},
                            {"(lambda () (raise (list 1 \"two\")))", "(1 \"two\")"},
                            {"(lambda () (error \"bad thing:\" 1 'x))", "bad thing: 1 x"},
                            {"(lambda () (car 1))", "car"},
                            {"(lambda () (raise-continuable 'again))", "again"},
            };
            for (String[] c : cases) {
                PolyglotException e = assertThrows(c[0], PolyglotException.class, () -> eval(context, c[0]).execute());
                assertTrue(c[0], e.isGuestException());
                assertFalse(c[0], e.isInternalError());
                assertFalse(c[0], e.isExit());
                assertTrue(c[0] + ": " + e.getMessage(), e.getMessage().contains(c[1]));
            }
            // Handlers inside the call work as usual.
            Value guarded = eval(context, "(lambda (x) (guard (e ((symbol? e) (list 'caught e)) ((string? e) 'string)) (raise x)))");
            assertEquals("(caught oops)", guarded.execute(eval(context, "'oops")).toString());
            assertEquals("string", guarded.execute("s").toString());
            // A guard with no matching clause re-raises, out to Java.
            PolyglotException e = assertThrows(PolyglotException.class, () -> guarded.execute(1));
            assertTrue(e.getMessage().contains("1"));
            Value continuable = eval(context, "(lambda () (with-exception-handler (lambda (c) 10) (lambda () (+ 1 (raise-continuable 'c)))))");
            assertEquals(11, continuable.execute().asInt());
        }
    }

    @Test
    public void anErrorLeavesTheDynamicEnvironmentAsItWas() {
        try (Context context = context()) {
            eval(context, "(define p (make-parameter 1))");
            Value fail = eval(context, "(lambda () (parameterize ((p 2)) (with-exception-handler (lambda (c) (raise 'again)) (lambda () (raise 'x)))))");
            assertThrows(PolyglotException.class, fail::execute);
            assertEquals(1, eval(context, "(p)").asInt());
            // No handler from the failed call is left behind: a raise is uncaught again.
            PolyglotException e = assertThrows(PolyglotException.class, () -> eval(context, "(raise 'plain)"));
            assertTrue(e.getMessage().contains("plain"));
            // The same for an error that leaves Context.eval.
            assertThrows(PolyglotException.class, () -> eval(context, "(parameterize ((p 3)) (car '()))"));
            assertEquals(1, eval(context, "(p)").asInt());
        }
    }

    @Test
    public void exitSurfacesAsExit() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            Value leave = eval(context, "(lambda (n) (dynamic-wind (lambda () #f) (lambda () (exit n)) (lambda () (display \"after\") (flush-output-port))))");
            leave.execute(4);
            fail("exit returned");
        } catch (PolyglotException e) {
            assertTrue(e.isExit());
            assertEquals(4, e.getExitStatus());
        }
        assertEquals("after", out.toString(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------------------------------------
    // Errors as Truffle exceptions (design.md D8, docs/embedding.md "Errors")

    private static PolyglotException error(Context context, Source source) {
        return assertThrows(source.getName(), PolyglotException.class, () -> context.eval(source));
    }

    private static Source source(String name, String text) {
        return Source.newBuilder(SCHEME, text, name).buildLiteral();
    }

    /**
     * Torture mode: every frame runs resumed from a continuation, and Truffle 25.5 doesn't see a
     * continuation's root as internal (docs/upstream.md, "Continuation roots are never internal"),
     * so the prelude's frames (map's) show in the trace, and {@link #guestFrames} leaves them out.
     */
    private static final boolean TORTURE = SchemeLanguage.constant(SchemeLanguage.TORTURE) != SchemeLanguage.Torture.OFF;

    /** The guest frames as {@code name line}, innermost first. */
    private static List<String> guestFrames(PolyglotException e) {
        List<String> frames = new ArrayList<>();
        for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
            SourceSection at = frame.getSourceLocation();
            if (frame.isGuestFrame() && !(TORTURE && at != null && at.getSource().isInternal())) {
                frames.add(frame.getRootName() + " " + (at == null ? "?" : at.getStartLine()));
            }
        }
        return frames;
    }

    private static String at(SourceSection section) {
        return section.getSource().getName() + ":" + section.getStartLine() + " " + section.getCharacters();
    }

    private static String at(com.oracle.truffle.api.source.SourceSection section) {
        return section.getSource().getName() + ":" + section.getStartLine() + " " + section.getCharacters();
    }

    /**
     * Another language, or Truffle code, sees a Scheme error through {@code InteropLibrary}: an
     * exception of type {@code RUNTIME_ERROR} with its message, no cause, the source location of
     * the Scheme code it came from, and its guest stack trace as an array of frames.
     */
    @Test
    public void errorsAnswerInteropsExceptionMessages() throws Exception {
        try (Context context = context()) {
            context.eval(source("lib.scm", """
                            (define (inner x)
                              (+ 1 (car x)))
                            (define (outer x)
                              (list (inner x)))
                            """));
            context.enter();
            try {
                SchemeContext scheme = SchemeContext.get(null);
                GlobalCell cell = new ProgramCompiler().interactionCell(scheme, scheme.symbols.intern("outer"));
                InteropLibrary interop = InteropLibrary.getUncached();
                AbstractTruffleException e = assertThrows(AbstractTruffleException.class, () -> interop.execute(cell.valueOrNull(), 5L));
                assertTrue(interop.isException(e));
                assertEquals(ExceptionType.RUNTIME_ERROR, interop.getExceptionType(e));
                assertEquals("car: not a pair: 5", interop.asString(interop.getExceptionMessage(e)));
                assertFalse(interop.hasExceptionCause(e));
                assertTrue(interop.hasSourceLocation(e));
                assertEquals("lib.scm:2 (car x)", at(interop.getSourceLocation(e)));
                // The stack trace: the prelude's frames (raise's) are internal, Scheme's are named and located.
                assertTrue(interop.hasExceptionStackTrace(e));
                Object trace = interop.getExceptionStackTrace(e);
                List<String> frames = new ArrayList<>();
                for (long i = 0; i < interop.getArraySize(trace); i++) {
                    Object frame = interop.readArrayElement(trace, i);
                    if (!interop.isInternal(frame) && interop.hasExecutableName(frame)) {
                        String name = interop.asString(interop.getExecutableName(frame));
                        frames.add(name + (interop.hasSourceLocation(frame) ? " " + at(interop.getSourceLocation(frame)) : ""));
                    }
                }
                assertEquals(List.of("inner lib.scm:2 (car x)", "outer lib.scm:4 (inner x)"), frames.subList(0, 2));
                // The exception is also the raised object: here, the error object.
                assertTrue(interop.hasMembers(e));
                assertEquals("car: not a pair: 5", interop.asString(interop.readMember(e, "message")));
                assertEquals("error-object", interop.asString(interop.getMetaSimpleName(interop.getMetaObject(e))));
            } finally {
                context.leave();
            }
        }
    }

    /**
     * {@code PolyglotException}'s message, source location and stack trace show where Scheme code
     * raised the error: the prelude's frames (map's, raise's) are internal, so neither the trace nor
     * the location is ever in the prelude.
     */
    @Test
    public void polyglotExceptionsLocateTheSchemeCode() {
        try (Context context = context()) {
            PolyglotException e = error(context, source("user.scm", """
                            (define (check x)
                              (map (lambda (y) (+ 1 (car y))) x))
                            (define (run x)
                              (list (check x)))
                            (run '((1) 2))
                            """));
            assertTrue(e.isGuestException());
            assertFalse(e.isExit() || e.isInterrupted() || e.isCancelled() || e.isInternalError() || e.isSyntaxError());
            assertEquals("car: not a pair: 2", e.getMessage());
            assertEquals("user.scm:2 (car y)", at(e.getSourceLocation()));
            assertEquals(List.of("<lambda@user.scm:2:8> 2", "run 4", "<program> 5", "<prompt> 1"), guestFrames(e));

            // In tail position, the error leaves the procedure's frame, as a tail call would: the
            // location is the innermost frame left, not map's in the prelude.
            e = error(context, source("tail.scm", """
                            (define (check x)
                              (map (lambda (y) (car y)) x))
                            (define (run x)
                              (list (check x)))
                            (run '((1) 2))
                            """));
            assertEquals("tail.scm:4 (check x)", at(e.getSourceLocation()));
            assertEquals(List.of("run 4", "<program> 5", "<prompt> 1"), guestFrames(e));

            // A syntax error is a parse error, located at its form; one that ends early is incomplete.
            e = error(context, source("syntax.scm", "(define (f)\n  (if))"));
            assertTrue(e.isSyntaxError());
            assertFalse(e.isIncompleteSource());
            assertEquals("syntax.scm:2 (if)", at(e.getSourceLocation()));
            e = error(context, source("open.scm", "(define (f)"));
            assertTrue(e.isSyntaxError());
            assertTrue(e.isIncompleteSource());
        }
    }

    /**
     * The guest stack trace has every frame the error left: those a deep recursion spilled to the
     * heap, and those of a continuation re-entered after its call returned.
     */
    @Test
    public void polyglotStackTracesHaveSpilledAndResumedFrames() {
        Context.Builder builder = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).allowExperimentalOptions(true);
        try (Context context = builder.option("scheme.SpillDepth", "5").build()) {
            PolyglotException e = error(context, source("deep.scm", """
                            (define (down n)
                              (if (= n 0)
                                  (list (car n))
                                  (+ 1 (down (- n 1)))))
                            (down 30)
                            """));
            assertEquals("deep.scm:3 (car n)", at(e.getSourceLocation()));
            List<String> frames = guestFrames(e);
            assertEquals(frames.toString(), 31, frames.stream().filter(f -> f.startsWith("down ")).count());
            assertEquals("down 3", frames.get(0));
            assertEquals(Collections.nCopies(30, "down 4"), frames.subList(1, 31));
            assertEquals(List.of("<program> 5", "<prompt> 1"), frames.subList(31, frames.size()));

            // Through map: its frames, spilled and resumed too, are the prelude's, so internal.
            e = error(context, source("map.scm", """
                            (define (down n)
                              (if (= n 0)
                                  (list (car n))
                                  (car (map (lambda (x) (down (- n 1))) '(1)))))
                            (down 30)
                            """));
            assertEquals("map.scm:3 (car n)", at(e.getSourceLocation()));
            frames = guestFrames(e);
            assertEquals(frames.toString(), "down 3", frames.get(0));
            assertEquals(frames.toString(), Collections.nCopies(30, "down 4"), frames.subList(1, 31));
            assertEquals(List.of("<program> 5", "<prompt> 1"), frames.subList(31, frames.size()));

            e = error(context, source("resumed.scm", """
                            (define k #f)
                            (define (wait n)
                              (let ((v (+ 1 (call/cc (lambda (c) (set! k c) n)))))
                                (list v)))
                            (define (go) (list (wait 1)))
                            (go)
                            (k 'not-a-number)
                            """));
            assertEquals("+: not a number: not-a-number", e.getMessage());
            assertEquals("resumed.scm:3 (+ 1 (call/cc (lambda (c) (set! k c) n)))", at(e.getSourceLocation()));
            // wait and go returned long ago: their frames come from the continuation.
            assertEquals(List.of("wait 3", "go 5", "<program> 6"), guestFrames(e).subList(0, 3));
        }
    }

    /** The exit a program ends with, from a context of its own (an exit closes it). */
    private static PolyglotException exit(String name, String text) {
        return exit(Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()), name, text);
    }

    private static PolyglotException exit(Context.Builder builder, String name, String text) {
        try (Context context = builder.build()) {
            context.eval(source(name, text));
        } catch (PolyglotException e) {
            assertTrue(name + ": " + e, e.isExit());
            return e;
        }
        throw new AssertionError(name + ": no exit");
    }

    /**
     * An exit is located as an error would be (design.md D8): at the program's call of
     * {@code exit} or {@code emergency-exit}, the innermost frame of code that isn't internal, not
     * in the prelude's {@code exit}; also when a trampoline runs the primitive (here, {@code apply}'s
     * tail call), and after a tail call, at the call of the procedure that made it.
     */
    @Test
    public void exitsAreLocatedAtTheProgramsCall() {
        PolyglotException e = exit("exit.scm", """
                        (define (run n)
                          (list (exit n)))
                        (run 3)
                        """);
        assertEquals(3, e.getExitStatus());
        assertEquals("exit.scm:2 (exit n)", at(e.getSourceLocation()));
        if (!TORTURE) {
            // Under torture, run's frame and the program's wait on the heap, and an exit's trace
            // (Truffle's) doesn't unwind through them as an error's does.
            assertEquals(List.of("run 2", "<program> 3"), guestFrames(e).subList(0, 2));
        }

        e = exit("emergency.scm", """
                        (define (run n)
                          (list (emergency-exit n)))
                        (run 4)
                        """);
        assertEquals(4, e.getExitStatus());
        assertEquals("emergency.scm:2 (emergency-exit n)", at(e.getSourceLocation()));

        e = exit("apply.scm", """
                        (define (run n)
                          (list (apply emergency-exit (list n))))
                        (run 5)
                        """);
        assertEquals(5, e.getExitStatus());
        assertEquals("apply.scm:2 (apply emergency-exit (list n))", at(e.getSourceLocation()));

        e = exit("top.scm", """
                        (display "before")
                        (exit 6)
                        (display "after")
                        """);
        assertEquals(6, e.getExitStatus());
        assertEquals("top.scm:2 (exit 6)", at(e.getSourceLocation()));

        // In tail position, as an error: the frame that made the tail call is gone.
        e = exit("tail.scm", """
                        (define (leave n)
                          (exit n))
                        (define (run n)
                          (list (leave n)))
                        (run 7)
                        """);
        assertEquals(7, e.getExitStatus());
        assertEquals("tail.scm:4 (leave n)", at(e.getSourceLocation()));

        // Deep enough to spill, so the outer frames wait on the heap. Under torture every frame
        // does, the caller of exit's too, and the location is that pending frame's.
        Context.Builder spilling = Context.newBuilder(SCHEME).out(new ByteArrayOutputStream()).allowExperimentalOptions(true).option("scheme.SpillDepth", "5");
        e = exit(spilling, "deep.scm", """
                        (define (down n)
                          (if (= n 0)
                              (list (exit 8))
                              (+ 1 (down (- n 1)))))
                        (down 30)
                        """);
        assertEquals(8, e.getExitStatus());
        assertEquals("deep.scm:3 (exit 8)", at(e.getSourceLocation()));
    }

    /**
     * The guest object is what {@code raise} raised (design.md D10): an exception, and also that
     * Scheme value, with its Scheme display string and meta object, a number or a boolean included.
     */
    @Test
    public void theGuestObjectIsTheRaisedObject() {
        try (Context context = context()) {
            String[][] cases = {
                            {"(raise 42)", "42", "number"},
                            {"(raise 1.5)", "1.5", "number"},
                            {"(raise #t)", "#t", "boolean"},
                            {"(raise 'boom)", "boom", "symbol"},
                            {"(raise \"text\")", "\"text\"", "string"},
                            {"(raise (list 1 2))", "(1 2)", "pair"},
                            {"(error \"bad:\" 1)", "#<error \"bad:\" (1)>", "error-object"},
            };
            Value identity = eval(context, "(lambda (x) x)");
            for (String[] c : cases) {
                PolyglotException e = assertThrows(c[0], PolyglotException.class, () -> eval(context, c[0]));
                Value raised = e.getGuestObject();
                assertTrue(c[0], raised.isException());
                assertEquals(c[0], c[1], raised.toString());
                assertEquals(c[0], c[2], raised.getMetaObject().getMetaSimpleName());
                // Passed back into Scheme, it is the raised object itself, no longer an exception.
                Value back = identity.execute(raised);
                assertFalse(c[0], back.isException());
                assertEquals(c[0], c[1], back.toString());
                // Thrown again, it is the same exception.
                PolyglotException again = assertThrows(c[0], PolyglotException.class, raised::throwException);
                assertEquals(c[0], e.getMessage(), again.getMessage());
            }
            assertEquals(42, assertThrows(PolyglotException.class, () -> eval(context, "(raise 42)")).getGuestObject().asInt());
            assertTrue(assertThrows(PolyglotException.class, () -> eval(context, "(raise #t)")).getGuestObject().asBoolean());
        }
    }

    /**
     * {@code Context.interrupt} stops a running evaluation with an interrupt, not a Scheme error:
     * Scheme handlers can't catch it, the after-thunks it leaves don't run, and the context goes on;
     * {@code close(true)} cancels it, and the context is closed.
     */
    @Test
    public void interruptAndCancelAreNotSchemeErrors() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            PolyglotException e = whileLooping(context, out, () -> context.interrupt(Duration.ofMinutes(2)));
            assertTrue(e.toString(), e.isInterrupted());
            assertFalse(e.toString(), e.isCancelled() || e.isExit() || e.isInternalError());
            // The context goes on, with none of the loop's handlers left.
            assertEquals(3, eval(context, "(+ 1 2)").asInt());
            PolyglotException uncaught = assertThrows(PolyglotException.class, () -> eval(context, "(raise 'still-uncaught)"));
            assertEquals("uncaught exception: still-uncaught", uncaught.getMessage());
        }
        out.reset();
        Context context = Context.newBuilder(SCHEME).out(out).build();
        PolyglotException e = whileLooping(context, out, () -> context.close(true));
        assertTrue(e.toString(), e.isCancelled());
        assertFalse(e.toString(), e.isInterrupted() || e.isExit() || e.isInternalError());
    }

    /** Runs a loop that can't end (in a dynamic-wind, under handlers) on a thread, stops it, and returns what it threw. */
    private static PolyglotException whileLooping(Context context, ByteArrayOutputStream out, ThrowingRunnable stop) throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread evaluation = new Thread(() -> {
            try {
                eval(context, """
                                (guard (e (#t 'caught))
                                  (with-exception-handler (lambda (e) 'handled)
                                    (lambda ()
                                      (dynamic-wind (lambda () (display "go") (flush-output-port))
                                                    (lambda () (let loop () (loop)))
                                                    (lambda () (display " after") (flush-output-port))))))
                                """);
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        evaluation.start();
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        while (out.size() == 0 && evaluation.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        stop.run();
        evaluation.join(TimeUnit.MINUTES.toMillis(2));
        assertFalse("still running", evaluation.isAlive());
        assertEquals("go", out.toString(StandardCharsets.UTF_8));
        if (!(thrown.get() instanceof PolyglotException e)) {
            throw new AssertionError("not a PolyglotException", thrown.get());
        }
        return e;
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    // ---------------------------------------------------------------------------------------------
    // Bindings

    @Test
    public void theInteractionEnvironmentIsTheTopScope() {
        try (Context context = context()) {
            eval(context, "(define (greet name) (string-append \"hello, \" name)) (define answer 42)");
            Value bindings = context.getBindings(SCHEME);
            assertEquals("hello, world", bindings.getMember("greet").execute("world").asString());
            assertEquals(42, bindings.getMember("answer").asInt());
            // Imported variables are there too; syntax and internal names are not.
            assertTrue(bindings.hasMember("car"));
            assertTrue(bindings.getMember("car").canExecute());
            assertTrue(bindings.getMemberKeys().contains("greet"));
            assertTrue(bindings.getMemberKeys().contains("vector-map"));
            assertFalse(bindings.hasMember("if"));
            assertFalse(bindings.hasMember("let"));
            List<String> keys = new ArrayList<>(bindings.getMemberKeys());
            assertTrue(keys.contains("car"));
            assertFalse(keys.contains("if"));
            // Internal names (the runtime's %-procedures) are readable but not listed.
            assertFalse(keys.contains("%call/ec"));
            assertTrue(bindings.hasMember("%call/ec"));
            assertNull(bindings.getMember("no-such-variable"));
            // putMember is (define name value): new names, redefinitions, and shadowed imports.
            bindings.putMember("limit", 10);
            assertEquals(11, eval(context, "(+ limit 1)").asInt());
            bindings.putMember("answer", "forty-two");
            assertTrue(eval(context, "(string? answer)").asBoolean());
            assertEquals("forty-two", bindings.getMember("answer").asString());
            bindings.putMember("car", eval(context, "cdr"));
            assertEquals("(2)", eval(context, "(car '(1 2))").toString());
            // Syntactic keywords can't be redefined this way.
            assertThrows(UnsupportedOperationException.class, () -> bindings.putMember("if", 1));
            assertThrows(UnsupportedOperationException.class, () -> bindings.removeMember("limit"));
            assertNotNull(bindings.toString());
        }
    }

    /**
     * The context buffers its standard output (design.md D10), and flushes it, down to the
     * embedder's stream, when an evaluation returns and when the context closes: so output is
     * never left behind in a buffer, the embedder's own included.
     */
    @Test
    public void outputIsFlushedToTheEmbeddersStreamOnReturnAndOnClose() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BufferedOutputStream embedders = new BufferedOutputStream(sink, 1 << 16);
        Context context = Context.newBuilder(SCHEME).out(embedders).build();
        try {
            eval(context, "(display \"first\")");
            assertEquals("first", sink.toString(StandardCharsets.UTF_8));
            Value write = eval(context, "(lambda (s) (display s))");
            write.execute(" second");
            assertEquals("first second", sink.toString(StandardCharsets.UTF_8));
            write.execute(" third");
        } finally {
            context.close();
        }
        assertEquals("first second third", sink.toString(StandardCharsets.UTF_8));
    }

    @Test
    public void programsDontChangeTheBindings() {
        try (Context context = context()) {
            // A program (first form import) has a top level of its own (D15).
            eval(context, "(import (scheme base)) (define private-name 1)");
            assertFalse(context.getBindings(SCHEME).hasMember("private-name"));
        }
    }
}
