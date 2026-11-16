package scheme.reader;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.source.SourceSection;

import scheme.runtime.value.SPair;
import scheme.runtime.value.SSymbol;
import scheme.runtime.value.SVector;

/**
 * A syntax object (design.md D3): a datum with its set of scopes and its source position. Compound
 * data hold syntax objects: a list is a chain of {@link SPair}s whose elements (and dotted tail) are
 * Syntax, a vector's elements are Syntax. Compile-time only: never a Scheme value.
 *
 * <p>Scope changes reach the children of a compound datum lazily (Flatt 2016): adding or flipping a
 * scope on a list or vector records it as pending, and {@link #open()} pushes the pending changes
 * one level down, to the children. So adding a scope costs O(1), not the size of the form. Pending
 * changes are a set of added scopes and a set of flipped scopes, which commute because no scope is
 * ever both added and flipped: binding and use-site scopes are added, macro-introduction scopes are
 * flipped (scheme.compiler.Expander).
 */
public final class Syntax {

    public final Object datum;
    public final ScopeSet scopes;
    /** null when the text had no {@code Source}. */
    public final SourceSection source;
    /** Scopes added to, and flipped on, this object but not yet on its children. */
    private final ScopeSet pendingAdds;
    private final ScopeSet pendingFlips;
    /** The datum with the pending changes pushed to its children, made on first {@link #open()}. */
    private Object opened;

    public Syntax(Object datum, ScopeSet scopes, SourceSection source) {
        this(datum, scopes, source, ScopeSet.EMPTY, ScopeSet.EMPTY);
    }

    private Syntax(Object datum, ScopeSet scopes, SourceSection source, ScopeSet pendingAdds, ScopeSet pendingFlips) {
        this.datum = datum;
        this.scopes = scopes;
        this.source = source;
        boolean compound = datum instanceof SPair || datum instanceof SVector;
        this.pendingAdds = compound ? pendingAdds : ScopeSet.EMPTY;
        this.pendingFlips = compound ? pendingFlips : ScopeSet.EMPTY;
    }

    /** The same object with exactly these scopes (an identifier's, when binding it). */
    public Syntax withScopes(ScopeSet newScopes) {
        return new Syntax(datum, newScopes, source, pendingAdds, pendingFlips);
    }

    /** The same datum at another source position. */
    public Syntax withSource(SourceSection newSource) {
        return new Syntax(datum, scopes, newSource, pendingAdds, pendingFlips);
    }

    /**
     * {@code o} with {@code adds} added and then {@code flips} flipped, on it and everything inside
     * it. Plain data (a symbol, pair or vector that isn't syntax, as a macro transformer may return)
     * becomes syntax with no source position; other plain data is returned as is.
     */
    public static Object adjust(Object o, ScopeSet adds, ScopeSet flips) {
        if (o instanceof Syntax s) {
            return new Syntax(s.datum, s.scopes.union(adds).flipAll(flips), s.source, s.pendingAdds.union(adds), s.pendingFlips.flipAll(flips));
        }
        if (o instanceof SSymbol || o instanceof SPair || o instanceof SVector) {
            return new Syntax(o, ScopeSet.EMPTY.union(adds).flipAll(flips), null, adds, flips);
        }
        return o;
    }

    /** {@code o} with {@code scope} added, on it and everything inside it. */
    public static Object addScope(Object o, Scope scope) {
        return adjust(o, ScopeSet.EMPTY.add(scope), ScopeSet.EMPTY);
    }

    /** {@code o} with {@code scope} flipped, on it and everything inside it. */
    public static Object flipScope(Object o, Scope scope) {
        return adjust(o, ScopeSet.EMPTY, ScopeSet.EMPTY.add(scope));
    }

    /**
     * The datum, with this object's pending scope changes pushed to its children: a list's spine is
     * copied, its elements and dotted tail adjusted; a vector is copied, its elements adjusted. The
     * same object every time. A circular list in code isn't detected here and doesn't terminate.
     */
    @TruffleBoundary
    public Object open() {
        if (opened != null) {
            return opened;
        }
        Object result = datum;
        if (!pendingAdds.isEmpty() || !pendingFlips.isEmpty()) {
            if (datum instanceof SPair p) {
                SPair head = new SPair(adjust(p.car, pendingAdds, pendingFlips), null);
                SPair last = head;
                Object rest = p.cdr;
                while (rest instanceof SPair q) {
                    SPair next = new SPair(adjust(q.car, pendingAdds, pendingFlips), null);
                    last.cdr = next;
                    last = next;
                    rest = q.cdr;
                }
                last.cdr = adjust(rest, pendingAdds, pendingFlips);
                result = head;
            } else if (datum instanceof SVector v) {
                Object[] elements = new Object[v.length];
                for (int i = 0; i < elements.length; i++) {
                    elements[i] = adjust(v.get(i), pendingAdds, pendingFlips);
                }
                result = SVector.of(elements, v.immutable);
            }
        }
        opened = result;
        return result;
    }

    /** {@code o}'s datum, opened ({@link #open()}) if it is syntax. */
    public static Object e(Object o) {
        return o instanceof Syntax s ? s.open() : o;
    }

    /**
     * The plain datum (syntax->datum): every syntax object replaced by its datum. Shared structure and
     * cycles (datum labels) are preserved. No Java recursion (AGENTS.md rule 4).
     */
    @TruffleBoundary
    public static Object toDatum(Object syntax) {
        IdentityHashMap<Object, Object> copies = new IdentityHashMap<>();
        Deque<Object[]> fills = new ArrayDeque<>();
        Object result = shell(syntax, copies, fills);
        while (!fills.isEmpty()) {
            Object[] fill = fills.pop();
            Object value = shell(fill[2], copies, fills);
            if (fill[0] instanceof SPair pair) {
                if ((Integer) fill[1] == 0) {
                    pair.car = value;
                } else {
                    pair.cdr = value;
                }
            } else {
                ((SVector) fill[0]).set((Integer) fill[1], value);
            }
        }
        return result;
    }

    /** The copy of {@code o}, allocated now and filled in later through {@code fills}. */
    private static Object shell(Object o, IdentityHashMap<Object, Object> copies, Deque<Object[]> fills) {
        Object datum = o;
        while (datum instanceof Syntax s) {
            datum = s.datum;
        }
        if (datum instanceof SPair p) {
            Object copy = copies.get(p);
            if (copy == null) {
                SPair pair = new SPair(null, null);
                copies.put(p, pair);
                fills.push(new Object[]{pair, 1, p.cdr});
                fills.push(new Object[]{pair, 0, p.car});
                copy = pair;
            }
            return copy;
        }
        if (datum instanceof SVector v) {
            Object copy = copies.get(v);
            if (copy == null) {
                if (!v.isObjects()) {
                    // Fixnums or flonums (M5-06): no syntax inside, so a copy with the same storage.
                    copy = v.copy(0, v.length, v.immutable);
                    copies.put(v, copy);
                    return copy;
                }
                SVector vector = new SVector(new Object[v.length], v.immutable);
                copies.put(v, vector);
                for (int i = v.length - 1; i >= 0; i--) {
                    fills.push(new Object[]{vector, i, v.get(i)});
                }
                copy = vector;
            }
            return copy;
        }
        return datum;
    }

    @Override
    public String toString() {
        return "#<syntax " + (source == null ? "" : "line " + source.getStartLine() + " ") + scopes + ">";
    }
}
