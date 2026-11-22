package scheme.runtime;

/**
 * The storage of a variable that is the target of {@code set!}, or a {@code letrec*} variable a
 * closure captures (design.md D2). Frames and closures hold the box, never the value, so frame
 * copies on continuation resume share it.
 *
 * <p>A fixnum that {@code BoxSet} stores from an unboxed {@code long} stays unboxed (design.md
 * "Calls Truffle doesn't inline"): {@link #value} is {@link #LONG}, and {@link #longValue} the
 * number. Anything else reads it through {@link #get}.
 */
public final class Box {

    /** {@link #value} when the value is {@link #longValue}. */
    static final Object LONG = new Object();

    Object value;
    long longValue;

    public Box(Object value) {
        this.value = value;
    }

    /** The value, boxed if it is a {@code long} kept unboxed. */
    public Object get() {
        Object v = value;
        return v == LONG ? (Object) longValue : v;
    }

    public void set(Object newValue) {
        value = newValue;
    }

    void setLong(long newValue) {
        longValue = newValue;
        value = LONG;
    }

    boolean holdsLong() {
        return value == LONG;
    }
}
