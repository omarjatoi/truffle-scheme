package scheme.reader;

import java.util.concurrent.atomic.AtomicLong;

/** A scope (Flatt 2016, design.md D3): an identity with an order, for {@link ScopeSet}. */
public final class Scope implements Comparable<Scope> {

    private static final AtomicLong NEXT = new AtomicLong();

    final long id = NEXT.incrementAndGet();

    /** Whether a macro use made it, to mark what its template introduced. */
    private final boolean introduction;

    public Scope() {
        this(false);
    }

    private Scope(boolean introduction) {
        this.introduction = introduction;
    }

    /**
     * A fresh scope for one macro use: identifiers that have it were introduced by the macro's
     * template, not written by its user. Code the user wrote can't refer to a variable such an
     * identifier binds, so the debugger leaves it out (plan.md M6-05).
     */
    public static Scope introduction() {
        return new Scope(true);
    }

    public boolean isIntroduction() {
        return introduction;
    }

    @Override
    public int compareTo(Scope other) {
        return Long.compare(id, other.id);
    }

    @Override
    public String toString() {
        return "scope" + id;
    }
}
