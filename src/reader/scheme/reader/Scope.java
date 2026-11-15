package scheme.reader;

import java.util.concurrent.atomic.AtomicLong;

/** A scope (Flatt 2016, design.md D3): an identity with an order, for {@link ScopeSet}. */
public final class Scope implements Comparable<Scope> {

    private static final AtomicLong NEXT = new AtomicLong();

    final long id = NEXT.incrementAndGet();

    @Override
    public int compareTo(Scope other) {
        return Long.compare(id, other.id);
    }

    @Override
    public String toString() {
        return "scope" + id;
    }
}
