package scheme.reader;

import java.util.Arrays;

/** An immutable set of {@link Scope}s, sorted by id. Sets are small, so this is an array. */
public final class ScopeSet {

    public static final ScopeSet EMPTY = new ScopeSet(new Scope[0]);

    private final Scope[] scopes;

    private ScopeSet(Scope[] scopes) {
        this.scopes = scopes;
    }

    public int size() {
        return scopes.length;
    }

    public boolean isEmpty() {
        return scopes.length == 0;
    }

    public boolean contains(Scope scope) {
        return Arrays.binarySearch(scopes, scope) >= 0;
    }

    public ScopeSet add(Scope scope) {
        int i = Arrays.binarySearch(scopes, scope);
        if (i >= 0) {
            return this;
        }
        int at = -i - 1;
        Scope[] result = new Scope[scopes.length + 1];
        System.arraycopy(scopes, 0, result, 0, at);
        result[at] = scope;
        System.arraycopy(scopes, at, result, at + 1, scopes.length - at);
        return new ScopeSet(result);
    }

    public ScopeSet remove(Scope scope) {
        int i = Arrays.binarySearch(scopes, scope);
        if (i < 0) {
            return this;
        }
        Scope[] result = new Scope[scopes.length - 1];
        System.arraycopy(scopes, 0, result, 0, i);
        System.arraycopy(scopes, i + 1, result, i, scopes.length - i - 1);
        return new ScopeSet(result);
    }

    /** Adds the scope if absent, removes it if present (macro-introduction scopes). */
    public ScopeSet flip(Scope scope) {
        return contains(scope) ? remove(scope) : add(scope);
    }

    public boolean isSubsetOf(ScopeSet other) {
        for (Scope s : scopes) {
            if (!other.contains(s)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ScopeSet s && Arrays.equals(scopes, s.scopes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(scopes);
    }

    @Override
    public String toString() {
        return Arrays.toString(scopes);
    }
}
