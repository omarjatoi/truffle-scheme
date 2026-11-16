package scheme.runtime;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * One Scheme object per Java object (design.md D10). Truffle wraps a Java object afresh each time
 * it crosses into the context, so the same object passed twice would be two foreign values that
 * {@code eq?} tells apart. The context keeps the first wrapper of each Java object it sees and
 * hands that one out again, holding both weakly: once Scheme has dropped the wrapper, a later
 * crossing gets a new one, and nothing could tell. The Java object's own identity decides, never
 * {@code equals}.
 */
final class HostObjects {

    /** A weak key compared by the identity of its referent. */
    private static final class Key extends WeakReference<Object> {
        private final int hash;

        Key(Object referent, ReferenceQueue<Object> queue) {
            super(referent, queue);
            this.hash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            Object referent = get();
            return other instanceof Key k && referent != null && referent == k.get();
        }
    }

    private final Map<Key, WeakReference<Object>> wrappers = new HashMap<>();
    private final ReferenceQueue<Object> cleared = new ReferenceQueue<>();
    /** The wrapper of Java's null, kept for good. */
    private Object hostNull;

    /** The wrapper to use for {@code hostObject}, of which {@code wrapper} is a fresh wrapper. */
    @TruffleBoundary
    Object canonical(Object wrapper, Object hostObject) {
        if (hostObject == null) {
            if (hostNull == null) {
                hostNull = wrapper;
            }
            return hostNull;
        }
        for (Object stale = cleared.poll(); stale != null; stale = cleared.poll()) {
            wrappers.remove(stale);
        }
        WeakReference<Object> known = wrappers.get(new Key(hostObject, null));
        Object canonical = known == null ? null : known.get();
        if (canonical == null) {
            wrappers.put(new Key(hostObject, cleared), new WeakReference<>(wrapper));
            canonical = wrapper;
        }
        return canonical;
    }
}
