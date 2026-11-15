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

    /** The {@code i}th scope, in id order. */
    public Scope get(int i) {
        return scopes[i];
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

    /** This set plus every scope in {@code other}. */
    public ScopeSet union(ScopeSet other) {
        if (other.isEmpty() || other == this) {
            return this;
        }
        if (isEmpty()) {
            return other;
        }
        ScopeSet result = this;
        for (Scope s : other.scopes) {
            result = result.add(s);
        }
        return result;
    }

    /** Flips every scope in {@code other}: the symmetric difference. */
    public ScopeSet flipAll(ScopeSet other) {
        if (other.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return other;
        }
        ScopeSet result = this;
        for (Scope s : other.scopes) {
            result = result.flip(s);
        }
        return result;
    }

    /** This set without any scope in {@code other}. */
    public ScopeSet minus(ScopeSet other) {
        ScopeSet result = this;
        for (Scope s : other.scopes) {
            result = result.remove(s);
        }
        return result;
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
