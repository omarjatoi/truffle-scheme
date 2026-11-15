package scheme.reader;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.source.SourceSection;

import scheme.runtime.value.SPair;
import scheme.runtime.value.SVector;

/**
 * A syntax object (design.md D3): a datum with its set of scopes and its source position. Compound
 * data hold syntax objects: a list is a chain of {@link SPair}s whose elements (and dotted tail) are
 * Syntax, a vector's elements are Syntax. Compile-time only: never a Scheme value.
 */
public final class Syntax {

    public final Object datum;
    public final ScopeSet scopes;
    /** null when the text had no {@code Source}. */
    public final SourceSection source;

    public Syntax(Object datum, ScopeSet scopes, SourceSection source) {
        this.datum = datum;
        this.scopes = scopes;
        this.source = source;
    }

    public Syntax withScopes(ScopeSet newScopes) {
        return new Syntax(datum, newScopes, source);
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
                ((SVector) fill[0]).elements[(Integer) fill[1]] = value;
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
                SVector vector = new SVector(new Object[v.elements.length], v.immutable);
                copies.put(v, vector);
                for (int i = v.elements.length - 1; i >= 0; i--) {
                    fills.push(new Object[]{vector, i, v.elements[i]});
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
