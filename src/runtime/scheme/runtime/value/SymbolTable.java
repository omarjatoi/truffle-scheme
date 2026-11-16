package scheme.runtime.value;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;

/**
 * Interns symbols. One table per context (design.md D1); contexts are single-threaded (D10).
 *
 * <p>The table holds its symbols weakly (plan.md M8-02): a symbol nothing else refers to is
 * garbage, so a program that makes symbols as it goes ({@code string->symbol}, {@code read})
 * keeps only those it holds on to. Interning a name whose symbol is still held gives that symbol
 * back, so {@code eq?} is identity as before; one that was collected couldn't be compared with
 * anything, and its name gets a new one.
 */
public final class SymbolTable {

    /** A weak reference to a symbol that knows its name, to remove its entry once it is cleared. */
    private static final class Ref extends WeakReference<SSymbol> {
        final String name;

        Ref(String name, SSymbol symbol, ReferenceQueue<SSymbol> queue) {
            super(symbol, queue);
            this.name = name;
        }
    }

    private final Map<String, Ref> symbols = new HashMap<>();
    private final ReferenceQueue<SSymbol> cleared = new ReferenceQueue<>();

    @TruffleBoundary
    public SSymbol intern(String name) {
        expunge();
        Ref ref = symbols.get(name);
        SSymbol symbol = ref == null ? null : ref.get();
        if (symbol == null) {
            symbol = new SSymbol(TruffleString.fromJavaStringUncached(name, TruffleString.Encoding.UTF_8));
            symbols.put(name, new Ref(name, symbol, cleared));
        }
        return symbol;
    }

    /** How many symbols the table holds, for tests. */
    @TruffleBoundary
    public int size() {
        expunge();
        return symbols.size();
    }

    /** Removes the entries of collected symbols, unless the name has a new symbol already. */
    private void expunge() {
        for (Object stale = cleared.poll(); stale != null; stale = cleared.poll()) {
            Ref ref = (Ref) stale;
            symbols.remove(ref.name, ref);
        }
    }
}
