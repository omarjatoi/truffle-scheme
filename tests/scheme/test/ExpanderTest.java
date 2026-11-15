package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.UnaryOperator;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.Test;

import scheme.compiler.Expander;
import scheme.compiler.SyntacticEnvironment;
import scheme.compiler.Transformer;
import scheme.reader.Syntax;
import scheme.runtime.GlobalEnvironment;
import scheme.runtime.SchemeError;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SSymbol;
import scheme.runtime.value.SymbolTable;

/**
 * M3-02: the sets-of-scopes expander (design.md D3). {@link Expander#show} prints the core AST with
 * each local variable renamed to {@code name.N}, so tests can see what every identifier resolved
 * to. Macros here are Java {@link Transformer}s; SyntaxRulesTest covers {@code syntax-rules}.
 */
public class ExpanderTest {

    private static final String SCHEME = "scheme";

    private final SymbolTable symbols = new SymbolTable();
    private final SyntacticEnvironment top = new SyntacticEnvironment();

    private String show(String program) {
        return Expander.show(symbols, new GlobalEnvironment(), top, program);
    }

    private SchemeError showError(String program) {
        return assertThrows(SchemeError.class, () -> show(program));
    }

    private SSymbol sym(String name) {
        return symbols.intern(name);
    }

    /** A list of the items (syntax or plain data). */
    private static Object list(Object... items) {
        Object result = SConstant.EMPTY_LIST;
        for (int i = items.length - 1; i >= 0; i--) {
            result = new SPair(items[i], result);
        }
        return result;
    }

    /** The {@code i}th element of a macro use. */
    private static Object arg(Object form, int i) {
        Object rest = Syntax.e(form);
        for (int k = 0; k < i; k++) {
            rest = Syntax.e(((SPair) rest).cdr);
        }
        return ((SPair) rest).car;
    }

    private void defineSyntax(String name, Transformer t) {
        top.defineSyntax(sym(name), t);
    }

    /** A macro whose transformer needs only the form. */
    private void defineMacro(String name, UnaryOperator<Object> expand) {
        defineSyntax(name, new Transformer() {
            @Override
            public Object transform(Object form, Expander expander) {
                return expand.apply(form);
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // Scope resolution

    @Test
    public void lexicalShadowing() {
        assertEquals("(lambda (x.1) (lambda (x.2) x.2))", show("(lambda (x) (lambda (x) x))"));
        assertEquals("(lambda (x.1) (lambda (y.2) x.1))", show("(lambda (x) (lambda (y) x))"));
        assertEquals("(lambda (x.1 . r.2) (r.2 x.1 y))", show("(lambda (x . r) (r x y))"));
        assertEquals("(lambda args.1 args.1)", show("(lambda args args)"));
        assertEquals("(letrec* ((f.1 (lambda (x.2) (f.1 x.2)))) (f.1 1))", show("(letrec* ((f (lambda (x) (f x)))) (f 1))"));
        // Globals keep their names; quoted data isn't resolved.
        assertEquals("(lambda (x.1) (begin (car x.1) 'x))", show("(lambda (x) (car x) 'x)"));
    }

    @Test
    public void keywordsShadowedByLocalVariables() {
        assertEquals("(lambda (if.1) (if.1 1 2 3))", show("(lambda (if) (if 1 2 3))"));
        assertEquals("(lambda (quote.1) (quote.1 x))", show("(lambda (quote) 'x)"));
        assertEquals("(letrec* ((lambda.1 1)) (lambda.1 (x) x))", show("(letrec* ((lambda 1)) (lambda (x) x))"));
        // Only inside the binding's scope.
        assertEquals("(begin (lambda (if.1) if.1) (if 1 2 3))", show("(lambda (if) if) (if 1 2 3)"));
        // A body definition shadows a keyword for the rest of the body.
        assertEquals("(lambda () (letrec* ((define.1 1)) define.1))", show("(lambda () (define define 1) define)"));
        // A top-level definition shadows a keyword (in the whole program, R7RS §5.1).
        assertEquals("(begin (define if list) (if 1 2 3))", show("(define if list) (if 1 2 3)"));
        // A keyword shadowed by a variable is a variable for set! too.
        assertEquals("(lambda (begin.1) (set! begin.1 1))", show("(lambda (begin) (set! begin 1))"));
    }

    @Test
    public void definitionContexts() {
        // Internal definitions are letrec*, in a scope inside the parameters'.
        assertEquals("(lambda (x.1) (letrec* ((x.2 2)) x.2))", show("(lambda (x) (define x 2) x)"));
        assertEquals("(lambda (n.1) (letrec* ((ev?.2 (lambda (k.3) (od?.4 k.3))) (od?.4 (lambda (k.5) (ev?.2 k.5)))) (ev?.2 n.1)))",
                        show("(lambda (n) (define (ev? k) (od? k)) (define (od? k) (ev? k)) (ev? n))"));
        // begin splices definitions into the body.
        assertEquals("(lambda () (letrec* ((a.1 1) (b.2 a.1)) b.2))", show("(lambda () (begin (define a 1) (begin (define b a))) b)"));
        // letrec* bodies are bodies too.
        assertEquals("(letrec* ((a.1 1)) (letrec* ((b.2 a.1)) b.2))", show("(letrec* ((a 1)) (define b a) b)"));
        // At the top level, definitions are globals.
        assertEquals("(begin (define f (lambda (x.1) x.1)) (f 1))", show("(define (f x) x) (f 1)"));
        assertEquals("(define g 1)", show("(begin (define g 1))"));
    }

    @Test
    public void syntaxErrors() {
        assertTrue(showError("(lambda (x x) x)").getMessage().contains("duplicate parameter x"));
        assertTrue(showError("(lambda () (define a 1) (define a 2) a)").getMessage().contains("duplicate definition of a"));
        assertTrue(showError("(lambda () (define a 1))").getMessage().contains("at least one expression"));
        assertTrue(showError("(lambda () 1 (define a 1) a)").getMessage().contains("only allowed at the top level or at the start of a body"));
        assertTrue(showError("(+ 1 if)").getMessage().contains("syntax keyword used as an expression"));
        assertTrue(showError("(set! lambda 1)").getMessage().contains("set! of a syntax keyword"));
        assertTrue(showError("(lambda (1) 1)").getMessage().contains("invalid parameter 1"));
        assertTrue(showError("(define-syntax m 5)").getMessage().contains("not a transformer"));
        assertTrue(showError("(include \"x.scm\")").getMessage().contains("cannot find file x.scm"));
        assertTrue(showError("(display 1)\n(syntax-rules ())").getMessage().contains("line 2"));
    }

    // ------------------------------------------------------------------------------------------
    // Macros

    /** (swap! a b): {@code ((lambda (tmp) (set! a b) (set! b tmp)) a)}, with tmp introduced. */
    private void defineSwap() {
        defineMacro("swap!", form -> {
            Object a = arg(form, 1);
            Object b = arg(form, 2);
            return list(list(sym("lambda"), list(sym("tmp")), list(sym("set!"), a, b), list(sym("set!"), b, sym("tmp"))), a);
        });
    }

    @Test
    public void introducedBindingsDontCaptureUseSiteReferences() {
        defineSwap();
        assertEquals("(lambda (tmp.1 other.2) ((lambda (tmp.3) (begin (set! tmp.1 other.2) (set! other.2 tmp.3))) tmp.1))",
                        show("(lambda (tmp other) (swap! tmp other))"));
    }

    @Test
    public void useSiteBindingsDontCaptureIntroducedReferences() {
        // (first x) is (car x): the introduced car is the global even where car is a local.
        defineMacro("first", form -> list(sym("car"), arg(form, 1)));
        assertEquals("(lambda (car.1) (car car.1))", show("(lambda (car) (first car))"));
        // Introduced keywords stay keywords where the use site rebinds them.
        defineSwap();
        assertEquals("(lambda (lambda.1 set!.2) ((lambda (tmp.3) (begin (set! lambda.1 set!.2) (set! set!.2 tmp.3))) lambda.1))",
                        show("(lambda (lambda set!) (swap! lambda set!))"));
    }

    @Test
    public void macrosExpandIntoDefinitions() {
        // (def-one name) is (define name 1): the use site's identifier is defined for the use site.
        defineMacro("def-one", form -> list(sym("define"), arg(form, 1), 1L));
        assertEquals("(lambda () (letrec* ((a.1 1)) a.1))", show("(lambda () (def-one a) a)"));
        assertEquals("(begin (define g 1) g)", show("(def-one g) g"));
        // (def-two a b) is a begin of two definitions, which splices into the body.
        defineMacro("def-two", form -> list(sym("begin"), list(sym("define"), arg(form, 1), 1L), list(sym("def-one"), arg(form, 2))));
        assertEquals("(lambda () (letrec* ((a.1 1) (b.2 1)) (a.1 b.2)))", show("(lambda () (def-two a b) (a b))"));
        // An introduced definition is invisible to the use site.
        defineMacro("def-hidden", form -> list(sym("begin"), list(sym("define"), sym("hidden"), arg(form, 1)), sym("hidden")));
        assertEquals("(lambda (hidden.1) (letrec* ((hidden.2 1)) (begin hidden.2 hidden.1)))", show("(lambda (hidden) (def-hidden 1) hidden)"));
        // After the first expression, a definition is an error even when a macro makes it.
        assertTrue(showError("(lambda () 1 (def-one a) a)").getMessage().contains("only allowed at the top level or at the start of a body"));
    }

    @Test
    public void localMacros() {
        defineSwap();
        // define-syntax in a body; let-syntax and letrec-syntax; each alias of swap! is a macro there.
        assertEquals("(lambda (a.1 b.2) ((lambda (tmp.3) (begin (set! a.1 b.2) (set! b.2 tmp.3))) a.1))",
                        show("(lambda (a b) (define-syntax sw swap!) (sw a b))"));
        assertEquals("(lambda (a.1 b.2) ((lambda (tmp.3) (begin (set! a.1 b.2) (set! b.2 tmp.3))) a.1))",
                        show("(lambda (a b) (let-syntax ((sw swap!)) (sw a b)))"));
        assertEquals("(lambda (a.1 b.2) ((lambda (tmp.3) (begin (set! a.1 b.2) (set! b.2 tmp.3))) a.1))",
                        show("(lambda (a b) (letrec-syntax ((sw swap!)) (sw a b)))"));
        // The keyword is bound only in the let-syntax body.
        assertEquals("(begin (lambda (a.1 b.2) ((lambda (tmp.3) (begin (set! a.1 b.2) (set! b.2 tmp.3))) a.1)) (sw 1 2))",
                        show("(lambda (a b) (let-syntax ((sw swap!)) (sw a b))) (sw 1 2)"));
        // A local variable shadows a top-level macro.
        assertEquals("(lambda (swap!.1) (swap!.1 1 2))", show("(lambda (swap!) (swap! 1 2))"));
        // A top-level alias.
        assertEquals("((lambda (tmp.1) (begin (set! x y) (set! y tmp.1))) x)", show("(define-syntax sw swap!) (sw x y)"));
    }

    @Test
    public void freeIdentifierEquals() {
        boolean[] same = new boolean[2];
        defineSyntax("same?", (form, expander) -> {
            same[0] = expander.freeIdentifierEquals(arg(form, 1), sym("else"));
            same[1] = expander.freeIdentifierEquals(arg(form, 1), arg(form, 2));
            return 0L;
        });
        show("(same? else else)");
        assertTrue(same[0] && same[1]);
        show("(lambda (else) (same? else foo))");
        assertTrue(!same[0] && !same[1]);
    }

    /** Rule 4: no Java recursion proportional to nesting depth. */
    @Test
    public void deepNestingDoesNotRecurse() {
        int depth = 100_000;
        String text = show("(begin " + "(if 1 ".repeat(depth) + "0" + " 2)".repeat(depth) + ")");
        assertEquals(depth, text.split("\\(if", -1).length - 1);
        int lambdas = 2_000;
        String nested = show("(lambda (x) ".repeat(lambdas) + "x" + ")".repeat(lambdas));
        assertTrue(nested.endsWith("x." + lambdas + ")".repeat(lambdas)));
    }

    // ------------------------------------------------------------------------------------------
    // End to end

    private static String output(String program) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(SCHEME).out(out).build()) {
            context.eval(SCHEME, program);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void shadowedKeywordsRun() {
        assertEquals("(1 2 3)", output("(display ((lambda (if) (if 1 2 3)) list))"));
        assertEquals("5", output("(define (f) (define define 5) define) (display (f))"));
        assertEquals("(a b)", output("(define (f x) (define x 'b) (list 'a x)) (display (f 'c))"));
    }

    @Test
    public void promptForm() {
        assertEquals("3", output("(display (%prompt 'p (+ 1 2)))"));
        assertEquals("6", output("(display (+ 1 (%prompt 'p (+ 10 (%capture-to-prompt 'p (lambda (k) 5))))))"));
    }

    @Test
    public void macroErrorsNameTheLine() {
        try (Context context = Context.create(SCHEME)) {
            PolyglotException e = assertThrows(PolyglotException.class, () -> context.eval(SCHEME, "(define x 1)\n(define-syntax m x)"));
            assertTrue(e.getMessage(), e.getMessage().contains("not a transformer") && e.getMessage().contains("line 2"));
        }
    }
}
