package scheme.runtime.value;

import java.util.Arrays;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;

/**
 * A vector. Literal vectors are immutable.
 *
 * <p>Storage strategies (plan.md M5-06): the elements live in a {@code long[]} while every one is a
 * fixnum, in a {@code double[]} while every one is a flonum, and otherwise in an {@code Object[]}.
 * A store that doesn't fit the storage (a non-fixnum into a {@code long[]}, a non-flonum into a
 * {@code double[]}) first moves the elements to an {@code Object[]}, for good: storage only ever
 * generalizes. Exactness is kept, so a fixnum never goes into a {@code double[]}. The array in
 * {@link #storage} always has exactly one of the three classes (never a subclass of
 * {@code Object[]}), so compiled code can cast it with {@link CompilerDirectives#castExact}.
 *
 * <p>The accessor nodes ({@code VectorNodes} in the builtins) specialize on the storage; everything
 * else goes through {@link #get}, {@link #set} and the bulk operations here.
 */
public final class SVector implements TruffleObject {

    private static final Object[] NO_ELEMENTS = {};

    /** The elements: a {@code long[]}, a {@code double[]} or an {@code Object[]}, of {@link #length}. */
    public Object storage;
    /** Vectors never change length. */
    public final int length;
    public final boolean immutable;

    /** A mutable vector with {@code Object[]} storage: {@code elements} itself, not a copy. */
    public SVector(Object[] elements) {
        this(elements, false);
    }

    /** A vector with {@code Object[]} storage: {@code elements} itself, not a copy, unless its class is a subclass. */
    public SVector(Object[] elements, boolean immutable) {
        this.storage = elements.getClass() == Object[].class ? elements : exactCopy(elements);
        this.length = elements.length;
        this.immutable = immutable;
    }

    @TruffleBoundary
    private static Object[] exactCopy(Object[] elements) {
        return Arrays.copyOf(elements, elements.length, Object[].class);
    }

    public SVector(long[] elements, boolean immutable) {
        this.storage = elements;
        this.length = elements.length;
        this.immutable = immutable;
    }

    public SVector(double[] elements, boolean immutable) {
        this.storage = elements;
        this.length = elements.length;
        this.immutable = immutable;
    }

    /** A vector of {@code elements}, in the narrowest storage that holds them all. May keep the array. */
    public static SVector of(Object[] elements, boolean immutable) {
        int n = elements.length;
        if (n == 0) {
            return new SVector(NO_ELEMENTS, immutable);
        }
        Object first = elements[0];
        if (first instanceof Long) {
            long[] longs = new long[n];
            for (int i = 0; i < n; i++) {
                if (!(elements[i] instanceof Long x)) {
                    return new SVector(elements, immutable);
                }
                longs[i] = x;
            }
            return new SVector(longs, immutable);
        }
        if (first instanceof Double) {
            double[] doubles = new double[n];
            for (int i = 0; i < n; i++) {
                if (!(elements[i] instanceof Double x)) {
                    return new SVector(elements, immutable);
                }
                doubles[i] = x;
            }
            return new SVector(doubles, immutable);
        }
        return new SVector(elements, immutable);
    }

    /** A mutable vector of {@code length} copies of {@code fill}, in the storage that fits it. */
    @TruffleBoundary
    public static SVector filled(int length, Object fill) {
        if (fill instanceof Long x) {
            long[] longs = new long[length];
            if (x != 0) {
                Arrays.fill(longs, x);
            }
            return new SVector(longs, false);
        }
        if (fill instanceof Double x) {
            double[] doubles = new double[length];
            Arrays.fill(doubles, x);
            return new SVector(doubles, false);
        }
        Object[] objects = new Object[length];
        Arrays.fill(objects, fill);
        return new SVector(objects, false);
    }

    public boolean isLongs() {
        return storage instanceof long[];
    }

    public boolean isDoubles() {
        return storage instanceof double[];
    }

    public boolean isObjects() {
        // An exact class check: storage is never a subclass of Object[] (see the class comment).
        return storage.getClass() == Object[].class;
    }

    /** The {@code long[]} storage; only after {@link #isLongs()}. */
    public long[] longs() {
        return CompilerDirectives.castExact(storage, long[].class);
    }

    /** The {@code double[]} storage; only after {@link #isDoubles()}. */
    public double[] doubles() {
        return CompilerDirectives.castExact(storage, double[].class);
    }

    /** The {@code Object[]} storage; only after {@link #isObjects()}. */
    public Object[] objects() {
        return CompilerDirectives.castExact(storage, Object[].class);
    }

    /** Whether {@code value} can be stored without generalizing the storage. */
    public boolean fits(Object value) {
        Object s = storage;
        return s instanceof Object[] || (s instanceof long[] ? value instanceof Long : value instanceof Double);
    }

    /** Element {@code i}, boxed. The index must be in range. */
    public Object get(int i) {
        Object s = storage;
        if (s instanceof long[] longs) {
            return longs[i];
        } else if (s instanceof double[] doubles) {
            return doubles[i];
        }
        return ((Object[]) s)[i];
    }

    /**
     * Stores {@code value} at {@code i}, generalizing the storage first if it doesn't fit. The index
     * must be in range; immutability is the caller's to check.
     */
    public void set(int i, Object value) {
        Object s = storage;
        if (s instanceof long[] longs && value instanceof Long x) {
            longs[i] = x;
        } else if (s instanceof double[] doubles && value instanceof Double x) {
            doubles[i] = x;
        } else {
            generalize()[i] = value;
        }
    }

    /** The storage as an {@code Object[]}, converting it (for good) if it is a primitive array. */
    @TruffleBoundary
    public Object[] generalize() {
        if (storage instanceof Object[] objects) {
            return objects;
        }
        Object[] objects = toArray();
        storage = objects;
        return objects;
    }

    /** A fresh {@code Object[]} of every element, boxed. */
    public Object[] toArray() {
        return toArray(0, length);
    }

    /** A fresh {@code Object[]} of the elements in {@code [start, end)}, boxed. */
    @TruffleBoundary
    public Object[] toArray(int start, int end) {
        Object s = storage;
        if (s instanceof Object[] objects) {
            return Arrays.copyOfRange(objects, start, end);
        }
        Object[] result = new Object[end - start];
        for (int i = start; i < end; i++) {
            result[i - start] = get(i);
        }
        return result;
    }

    /** A new mutable vector of the elements in {@code [start, end)}, with this vector's kind of storage. */
    public SVector copy(int start, int end) {
        return copy(start, end, false);
    }

    /** A new vector of the elements in {@code [start, end)}, with this vector's kind of storage. */
    @TruffleBoundary
    public SVector copy(int start, int end, boolean immutableCopy) {
        Object s = storage;
        if (s instanceof long[] longs) {
            return new SVector(Arrays.copyOfRange(longs, start, end), immutableCopy);
        } else if (s instanceof double[] doubles) {
            return new SVector(Arrays.copyOfRange(doubles, start, end), immutableCopy);
        }
        return new SVector(Arrays.copyOfRange((Object[]) s, start, end), immutableCopy);
    }

    /**
     * A new mutable vector of {@code n} elements with this vector's kind of storage, for the caller to
     * fill: its elements are 0, 0.0 or null until then.
     */
    @TruffleBoundary
    public SVector blank(int n) {
        Object s = storage;
        if (s instanceof long[]) {
            return new SVector(new long[n], false);
        } else if (s instanceof double[]) {
            return new SVector(new double[n], false);
        }
        return new SVector(new Object[n], false);
    }

    /** Stores {@code value} at every index in {@code [start, end)}, generalizing first if it doesn't fit. */
    @TruffleBoundary
    public void fill(int start, int end, Object value) {
        if (start == end) {
            return;
        }
        Object s = storage;
        if (s instanceof long[] longs && value instanceof Long x) {
            Arrays.fill(longs, start, end, x);
        } else if (s instanceof double[] doubles && value instanceof Double x) {
            Arrays.fill(doubles, start, end, x);
        } else {
            Arrays.fill(generalize(), start, end, value);
        }
    }

    /**
     * Copies {@code from}'s elements in {@code [start, end)} to this vector at {@code at}, correctly when
     * the two ranges overlap. Generalizes this vector's storage only if an element doesn't fit it.
     */
    @TruffleBoundary
    public void copyFrom(int at, SVector from, int start, int end) {
        Object s = storage;
        Object f = from.storage;
        if (s.getClass() == f.getClass()) {
            System.arraycopy(f, start, s, at, end - start);
        } else if (s instanceof Object[] objects) {
            for (int i = start; i < end; i++) {
                objects[at + i - start] = from.get(i);
            }
        } else {
            // Different vectors (one vector has one storage), so the ranges don't overlap.
            for (int i = start; i < end; i++) {
                set(at + i - start, from.get(i));
            }
        }
    }
}
