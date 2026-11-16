package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Value;
import org.junit.Test;

import scheme.runtime.SchemeContext;
import scheme.runtime.SchemeLanguage;
import scheme.runtime.value.SSymbol;
import scheme.runtime.value.SymbolTable;

/**
 * What a closed context, or a program, no longer needs is garbage (plan.md M8-02): nothing the
 * language registers with the engine or keeps in a table holds on to it.
 */
public class RetentionTest {

    private static final String SCHEME = "scheme";

    /**
     * Contexts of an embedder's shared engine (docs/embedding.md): once one is closed, its language
     * instance and context are garbage, though the engine lives on. Each context used to add a
     * listener to the engine's allocation reporter (for {@code --memtracer}) that was never
     * removed, which kept every closed context's language instance, its source cache and its code.
     */
    @Test
    public void closedContextsOfASharedEngineAreCollected() {
        try (Engine engine = Engine.newBuilder().option("engine.WarnInterpreterOnly", "false").build()) {
            List<WeakReference<Object>> refs = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                try (Context context = Context.newBuilder(SCHEME).engine(engine).build()) {
                    context.eval(SCHEME, "(import (scheme base)) (define (f x) (* x x)) (f 12)");
                    context.enter();
                    try {
                        refs.add(new WeakReference<>(SchemeLanguage.get(null)));
                        refs.add(new WeakReference<>(SchemeContext.get(null)));
                    } finally {
                        context.leave();
                    }
                }
            }
            for (WeakReference<Object> ref : refs) {
                assertCollected(ref);
            }
        }
    }

    /**
     * A symbol nothing refers to is garbage, as in most Schemes: a program that makes symbols from
     * strings as it goes ({@code string->symbol}, {@code read}) keeps only the ones it holds. A
     * symbol it still holds is the one interning its name gives back, so {@code eq?} is unchanged.
     */
    @Test
    public void unreferencedSymbolsAreCollected() {
        try (Context context = Context.create(SCHEME)) {
            context.initialize(SCHEME);
            context.enter();
            try {
                SymbolTable symbols = SchemeContext.get(null).symbols;
                SSymbol kept = symbols.intern("m8-02-kept");
                WeakReference<SSymbol> dropped = new WeakReference<>(symbols.intern("m8-02-dropped"));
                assertCollected(dropped);
                assertSame(kept, symbols.intern("m8-02-kept"));
                assertEquals("m8-02-dropped", symbols.intern("m8-02-dropped").toString());
            } finally {
                context.leave();
            }
            // A program that makes a million symbols and keeps the last: the table doesn't keep the rest.
            Value names = context.eval(SCHEME, """
                            (import (scheme base))
                            (let loop ((i 0) (last #f))
                              (if (= i 1000000)
                                  (eq? last (string->symbol (string-append "s" (number->string (- i 1)))))
                                  (loop (+ i 1) (string->symbol (string-append "s" (number->string i))))))
                            """);
            assertTrue(names.asBoolean());
            context.enter();
            try {
                SymbolTable symbols = SchemeContext.get(null).symbols;
                for (int i = 0; i < 50 && symbols.size() > 100_000; i++) {
                    collectGarbage();
                }
                assertTrue("symbols kept: " + symbols.size(), symbols.size() <= 100_000);
            } finally {
                context.leave();
            }
        }
    }

    /** Collects garbage until {@code ref} is cleared, or fails. */
    static void assertCollected(WeakReference<?> ref) {
        for (int i = 0; i < 50 && ref.get() != null; i++) {
            collectGarbage();
        }
        assertNull("still reachable after 50 collections: " + ref.get(), ref.get());
    }

    /** A collection, and time for the cleared references to be enqueued. */
    private static void collectGarbage() {
        System.gc();
        // Some garbage, so that a collector that skips an explicit request still runs.
        byte[][] garbage = new byte[64][];
        for (int k = 0; k < garbage.length; k++) {
            garbage[k] = new byte[1 << 16];
        }
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
